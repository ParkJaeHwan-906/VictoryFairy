package com.skhynix.user.game.realtime;

import com.skhynix.user.game.dto.GameResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 이 파드에 붙어 있는 경기 SSE 구독({@link SseEmitter})의 레지스트리.
 *
 * <p>구독은 <b>날짜</b>(전체 경기, {@code GET /games/subscribe}) 또는 <b>날짜+구단</b>(응원 구단 경기,
 * {@code GET /games/support/subscribe}) 키로 묶인다. 경기 하나의 갱신은 그 날짜 키 하나와 홈·원정 구단 키
 * 둘, 최대 세 묶음에 전달된다({@link #publish}). 구독자가 어느 경기를 보고 있는지는 서버가 추적하지 않는다 —
 * {@code GET /games}가 날짜 단위 목록인 것과 같은 단위다.
 *
 * <p>quiz 모듈의 {@code SseEmitterRegistry}(채팅)와 같은 역할이지만 따로 둔다: 저쪽은 사용자 단위 축출·명시적
 * 퇴장 명령이 핵심이고 여기는 그게 없다(같은 사용자가 두 화면에서 두 번 구독해도 둘 다 유지한다).
 * 공용 모듈로 올리면 두 앱의 수명주기 요구가 섞인다.
 *
 * <p>하트비트는 {@code @Scheduled}가 아니라 전용 스레드다 — user 모듈의 {@code @EnableScheduling}은
 * {@code CleanupSchedulingConfig}가 정리 스위치에 조건부로 켜므로(로컬 기본 꺼짐) 거기에 기대면 로컬에서
 * 하트비트가 안 돌아 CloudFront {@code origin_read_timeout}(30초) 전에 스트림이 끊긴다.
 */
@Component
public class GameSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(GameSseRegistry.class);

    static final String SNAPSHOT_EVENT = "snapshot";
    static final String UPDATE_EVENT = "game-update";

    /** 채팅 SSE와 같은 값. 브라우저 EventSource는 끊기면 스스로 재접속하고 그때 snapshot을 다시 받는다. */
    static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * CloudFront 오리진 읽기 타임아웃(modules/cdn {@code origin_read_timeout}, 30초)보다 짧아야 한다 —
     * 채팅 SSE 하트비트와 같은 주기다. 늘리려면 인프라 값도 함께 올려야 한다.
     */
    static final long HEARTBEAT_MS = 15_000L;

    private final Map<String, Set<SseEmitter>> subscriptions = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeat;

    public static String dateKey(LocalDate date) {
        return date.toString();
    }

    public static String teamKey(LocalDate date, Long teamId) {
        return date + "|team:" + teamId;
    }

    /** 새 구독을 만들어 키에 등록한다. 완료·타임아웃·오류 시 스스로 빠진다. */
    public SseEmitter register(String key) {
        return register(key, new SseEmitter(EMITTER_TIMEOUT_MS));
    }

    /**
     * 주어진 emitter를 키에 등록한다(테스트가 spy를 끼워 넣는 경로).
     *
     * <p>add까지 compute 람다 안에서 끝낸다 — 밖에서 add하면 마지막 구독 해지로 Set이 맵에서 걷힌 직후 같은
     * Set에 들어가는 레이스가 나 그 구독이 팬아웃·하트비트에서 통째로 누락된다(채팅 레지스트리와 같은 이유).
     */
    SseEmitter register(String key, SseEmitter emitter) {
        subscriptions.compute(key, (k, current) -> {
            Set<SseEmitter> set = (current == null) ? ConcurrentHashMap.newKeySet() : current;
            set.add(emitter);
            return set;
        });
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(key, emitter);
        });
        emitter.onError(error -> remove(key, emitter));
        return emitter;
    }

    /**
     * 연결 직후 현재 목록을 {@code snapshot} 이벤트로 보낸다. 핸들러가 emitter를 반환하기 전에 불러도 된다 —
     * {@code SseEmitter}는 응답이 열리기 전의 send를 버퍼에 두었다가 첫 플러시에 함께 내보낸다.
     */
    public void sendSnapshot(SseEmitter emitter, List<GameResponse> games) {
        try {
            emitter.send(SseEmitter.event().name(SNAPSHOT_EVENT).data(games));
        } catch (Exception e) {
            // 아직 등록 전이거나 이미 끊긴 연결 — 레지스트리 정리는 onError/onCompletion 콜백이 한다.
            log.debug("snapshot 전송 실패", e);
        }
    }

    /** 경기 갱신을 그 날짜 구독자와 홈·원정 구단 구독자에게 {@code game-update}로 전달한다. */
    public void publish(GameUpdateEvent event) {
        LocalDate date = event.date();
        GameResponse game = event.game();
        send(dateKey(date), event);
        send(teamKey(date, game.homeTeamId()), event);
        if (!game.homeTeamId().equals(game.awayTeamId())) {
            send(teamKey(date, game.awayTeamId()), event);
        }
    }

    public int count(String key) {
        Set<SseEmitter> set = subscriptions.get(key);
        return set == null ? 0 : set.size();
    }

    private void send(String key, GameUpdateEvent event) {
        Set<SseEmitter> set = subscriptions.get(key);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(UPDATE_EVENT).data(event));
            } catch (Exception e) {
                remove(key, emitter);
            }
        }
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    void heartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : subscriptions.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(String key, SseEmitter emitter) {
        subscriptions.computeIfPresent(key, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }
}
