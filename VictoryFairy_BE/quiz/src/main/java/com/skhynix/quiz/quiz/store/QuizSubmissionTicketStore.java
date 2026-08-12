package com.skhynix.quiz.quiz.store;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 제출 자격 증명(티켓) 저장소. 키 하나가 <b>두 가지 역할</b>을 겸한다 — <b>존재 여부는 자격</b>
 * ({@code /today}로 그 문제를 받았고 아직 시한이 남았다), <b>값은 이닝</b>(그 문제를 받은 시점의
 * {@code games.current_inning})이다.
 *
 * <p>키는 {@code quiz:inning:{userAccountId}:{quizId}}. 접두사는 필수다 — 같은 Redis 인스턴스를
 * {@code user} 앱의 {@code email:verify:*}와 실시간 채널 {@code realtime:events}가 공유한다.
 * 계정이 키에 들어가므로 남의 티켓을 읽을 경로가 없다.
 *
 * <p><b>프로파일로 갈리지 않는다.</b> 인메모리 폴백을 두면 dev·prod 동작이 달라져 TTL·403 계약을
 * 로컬에서 검증할 수 없고, 파드가 여럿인 환경에서는 {@code /today}와 제출이 서로 다른 파드로 가
 * 자격 검사가 무작위로 깨진다. 쓰는 것은 자동설정이 전 프로파일에 등록하는
 * {@link StringRedisTemplate} 뿐이라 <b>기동 시 연결을 맺는 빈이 늘지 않는다</b>(실시간 fan-out 의
 * {@code @Profile("prod")} 리스너 컨테이너와는 다른 축 — Redis 장애가 파드 기동 실패가 되면 안 된다).
 *
 * <p><b>Redis 실패를 삼키지 않는다</b>(발급·조회). 장애를 "티켓 없음"으로 접으면 장애 중인 사용자에게
 * "당신은 자격이 없다"(403)고 잘못 알리게 된다 — 예외는 그대로 올려 500 이 되게 둔다. 유일한 예외는
 * 커밋 후 삭제이며 그 판단은 호출부({@code QuizSubmitService})가 한다.
 */
@Repository
@RequiredArgsConstructor
public class QuizSubmissionTicketStore {

    private static final String KEY_PREFIX = "quiz:inning:";
    private static final Duration TTL = Duration.ofMinutes(8);

    /**
     * 이닝 미상 마커. 1~11 어떤 값과도 겹치지 않아 Redis 응답만 보고 "자격은 있으나 이닝은 모른다"를
     * 판정할 수 있다. <b>이닝을 못 구했다고 키를 만들지 않으면 안 된다</b> — 원천({@code py-collector})
     * 미구현으로 지금은 이닝 확보 실패가 100% 라, 그러면 모든 제출이 403 이 되어 서비스가 멈춘다.
     */
    private static final String UNKNOWN_INNING = "-";

    private final StringRedisTemplate redisTemplate;

    /**
     * {@code /today} 응답에 실린 문제들의 티켓을 한꺼번에 발급한다(값 없는 이닝은 미상 마커).
     * 이미 있는 키는 값과 TTL 을 함께 덮어쓴다 — 마지막으로 받은 이닝이 기록되고, 그 갱신이 곧
     * 시한 연장 수단이다.
     *
     * <p><b>왕복 횟수가 항목 수에 비례하면 안 된다</b>(상한이 20 이라 방치하면 왕복 20 회가 응답
     * 시간에 그대로 실린다) — 그래서 파이프라인으로 한 번에 보낸다. 그러면서도 {@code SETEX} 를
     * 항목마다 보내 <b>TTL 은 개별</b>로 걸린다(일괄 전송이 만료를 공유하게 만들지 않는다).
     */
    public void issue(long userAccountId, Map<Long, Integer> inningByQuizId) {
        if (inningByQuizId.isEmpty()) {
            return;
        }
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            // ⚠ 콜백 인자를 StringRedisConnection 으로 캐스팅하는 흔한 관용구를 쓰면 안 된다 —
            //   Spring Data Redis 4.1 의 StringRedisTemplate 은 연결을 DefaultStringRedisConnection
            //   으로 더 이상 감싸지 않아(preProcessConnection 오버라이드가 사라졌다) 런타임
            //   ClassCastException 이 된다. 바이트 명령을 직접 쓰고 직렬화만 맞춘다.
            inningByQuizId.forEach((quizId, inning) -> connection.stringCommands().setEx(
                    bytes(key(userAccountId, quizId)), TTL.toSeconds(), bytes(value(inning))));
            return null; // 파이프라인 콜백은 반드시 null 을 반환해야 한다(Spring Data 계약)
        });
    }

    /**
     * 티켓 조회. {@code Optional.empty()} 는 <b>자격 없음</b>(미발급 또는 시한 경과)이고, 값이 있으면
     * 자격이 있다 — {@link Ticket#inning()} 이 {@code null} 이어도 자격은 유효하다(이닝만 모를 뿐).
     */
    public Optional<Ticket> find(long userAccountId, long quizId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(userAccountId, quizId)))
                .map(Ticket::of);
    }

    /** 티켓 한 개만 지운다 — 한 문제를 냈다고 나머지 문제의 자격까지 사라지면 안 된다. */
    public void delete(long userAccountId, long quizId) {
        redisTemplate.delete(key(userAccountId, quizId));
    }

    /**
     * 파이프라인에서 쓸 직렬화. {@link StringRedisTemplate}의 기본 직렬화(UTF-8 문자열)와 같아야
     * 파이프라인으로 쓴 키를 {@link #find}의 {@code opsForValue().get}이 그대로 읽는다.
     */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String key(long userAccountId, long quizId) {
        return KEY_PREFIX + userAccountId + ":" + quizId;
    }

    private static String value(Integer inning) {
        return inning == null ? UNKNOWN_INNING : String.valueOf(inning);
    }

    /** 발급된 티켓. {@code inning} 이 {@code null} 이면 "자격은 있으나 이닝 미상"이다. */
    public record Ticket(Integer inning) {

        private static Ticket of(String value) {
            if (UNKNOWN_INNING.equals(value)) {
                return new Ticket(null);
            }
            try {
                return new Ticket(Integer.valueOf(value));
            } catch (NumberFormatException e) {
                // 우리가 쓰지 않은 형식의 값 — 자격은 인정하되(키는 있다) 이닝은 모르는 것으로 둔다.
                // 여기서 실패로 접으면 키 포맷이 바뀐 배포 직후 제출이 전부 막힌다.
                return new Ticket(null);
            }
        }
    }
}
