package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitStateView;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 조회. '오늘'은 KST 다({@code kstClock} — 파드 JVM 은 UTC 라 기본 클록이면 자정~09시에
 * 하루가 어긋난다).
 *
 * <p>보기는 {@code quiz_id IN (...)} 한 방으로 받아 메모리에서 묶는 <b>2쿼리 방식</b>이다 —
 * 문제마다 단건 조회하면 N+1 이고, {@code Quiz}에 {@code @OneToMany options}가 없어
 * {@code @EntityGraph}로는 못 막는다({@code QuizOptionRepository} javadoc 의 지시).
 *
 * <p><b>선호(preferred) 판정은 SQL 이 아니라 메모리에서 한다</b> — 세트가 하루 최대 십수 건이라
 * 전체를 이미 다 읽는데, 응원팀·응원선수 조건을 쿼리에 넣으면 "선호 먼저" 정렬을 위해 결국
 * 두 번 읽거나 CASE 정렬이 필요해진다. 대상 FK 는 목록 {@code @EntityGraph}가 함께 실어 와
 * id 비교에 LAZY 초기화가 없다.
 *
 * <p>오늘 세트의 노출 순서는 <b>선호 먼저, 각 그룹 안에서는 사용자별 고정 랜덤</b>이다
 * ({@link #shuffleKey}). 전 사용자가 같은 순서(id ASC)로 보면 앞쪽 문제에만 제출이 몰린다.
 */
@Service
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserSupportPlayerRepository userSupportPlayerRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final QuizLikeService quizLikeService;
    private final Clock clock;
    private final int maxTodayCount;

    // 상한을 설정값으로 두는 이유: "우선 20 으로 하고 추후 조정" 이므로 조정에 배포가 필요하면
    // 안 된다. 편성 수(quiz.serve.daily-count)와는 별개 축이라 키를 재사용하지 않는다 —
    // 한 값에 묶으면 그날 세트 크기를 바꾸지 않고는 노출 개수를 못 바꾼다.
    public QuizService(QuizRepository quizRepository, QuizOptionRepository quizOptionRepository,
            UserSupportTeamRepository userSupportTeamRepository,
            UserSupportPlayerRepository userSupportPlayerRepository,
            QuizUserSubmitRepository quizUserSubmitRepository, QuizLikeService quizLikeService,
            Clock clock, @Value("${quiz.serve.max-today-count:20}") int maxTodayCount) {
        this.quizRepository = quizRepository;
        this.quizOptionRepository = quizOptionRepository;
        this.userSupportTeamRepository = userSupportTeamRepository;
        this.userSupportPlayerRepository = userSupportPlayerRepository;
        this.quizUserSubmitRepository = quizUserSubmitRepository;
        this.quizLikeService = quizLikeService;
        this.clock = clock;
        this.maxTodayCount = maxTodayCount;
    }

    /**
     * 오늘(KST) 세트를 선호 먼저(그 안에서는 사용자별 랜덤) 정렬해 반환한다. 선호 = 내 응원팀이
     * 문제의 대상·상대 구단이거나, 문제의 대상 선수가 내 응원 선수인 것. 랜덤은 그룹 <b>안에서만</b>
     * 일어나므로 선호 문제가 비선호보다 뒤로 밀리는 일은 없다.
     *
     * <p><b>제외 기준은 "행이 있는가"가 아니라 "답했거나 시한이 지났는가"다.</b> 행은 이제 이 메서드가
     * 서빙하면서 만들므로(아래), 전자로 판정하면 <b>문제를 받는 순간 목록에서 사라져 새로고침·앱 복귀·
     * 네트워크 재시도 한 번에 그날 세트를 통째로 못 풀게 된다.</b> 그래서 답한 문제만 감추고(푼 문제
     * 비노출 정책 유지), 시한이 남은 미답 문제는 <b>계속 다시 실어 준다</b>. 시한을 넘긴 미답 문제는
     * 제외되고 복구 경로가 없다(미제출로 확정 — 내지 않으면 틀린 것).
     *
     * <p>선호 조회보다 먼저 거르는 이유: 다 푼 사용자는 응원 정보 조회 없이 빈 배열로 끝난다. 그래서
     * 빈 배열은 "오늘 세트 없음"과 "오늘 다 품" 두 경우를 모두 뜻한다 — 구분이 필요하면 풀이 이력
     * API 가 담당한다.
     *
     * <p>{@code preferredOnly=true}는 선호 문제만 남긴다. 단 <b>응원팀도 응원 선수도 없으면 필터
     * 기준 자체가 없으므로 전체를 반환</b>한다(no-op) — 빈 배열을 주면 위의 두 경우와 구분이
     * 안 되는데, 실제로는 취향을 아직 안 정했을 뿐이다.
     *
     * <p><b>응답에 실은 문제마다 미답 행을 만든다 — 그래서 이 조회는 쓰기 트랜잭션이다</b>
     * ({@code readOnly = true} 가 아니다). 그 행 하나가 세 역할을 겸한다: <b>존재는 제출 자격</b>
     * ({@code /today}를 거쳐 받았다), <b>{@code created_at} + 8분은 시한</b>, <b>{@code inning}은 받은
     * 시점의 이닝</b>. 앱이 강제 종료돼도 "받았고 안 냈다"는 사실이 이미 MySQL 에 있으므로 만료를
     * 뒤늦게 알아낼 장치(스윕·만기 인덱스)가 필요 없다.
     *
     * <p>행 생성이 실패하면 목록도 주지 않는다(같은 트랜잭션 — 부분 성공 금지). 행 없는 목록은 전부
     * 403 이 되어 "받았는데 못 내는" 상태가 되기 때문이다. 상한에 잘려 나간 문제에는 행을 만들지
     * 않으므로 그 문제의 제출은 403 이다.
     */
    @Transactional
    public List<QuizResponse> getTodayQuizzes(Long userAccountId, boolean preferredOnly) {
        List<Quiz> published = quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(clock));
        if (published.isEmpty()) {
            return List.of();
        }

        // ⚠ 시한 계산의 기준은 kstClock 이 아니다 — 비교 대상 created_at 이 JVM 기본 존으로 찍히기
        //   때문이다(QuizSubmitWindow javadoc). 위 quiz_date 조회만 kstClock 을 쓴다.
        LocalDateTime now = QuizSubmitWindow.now();
        // 한 번의 조회로 두 가지를 얻는다: 제외 대상(답했거나 시한 초과)과, 이미 행이 있어 INSERT 가
        // 필요 없는 문제. 차집합 재료를 위해 조회를 새로 늘리지 않는 것이 계약이다.
        Set<Long> excludedIds = new HashSet<>();
        Set<Long> rowExistingIds = new HashSet<>();
        for (QuizUserSubmitStateView state : quizUserSubmitRepository.findSubmitStates(
                userAccountId, published.stream().map(Quiz::getId).toList())) {
            rowExistingIds.add(state.getQuizId());
            if (state.getSubmitOptionId() != null
                    || QuizSubmitWindow.isExpired(state.getCreatedAt(), now)) {
                excludedIds.add(state.getQuizId());
            }
        }
        List<Quiz> quizzes = published.stream()
                .filter(quiz -> !excludedIds.contains(quiz.getId()))
                .toList();
        if (quizzes.isEmpty()) {
            return List.of();
        }

        Long supportTeamId = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(supportTeam -> supportTeam.getTeam().getId())
                .orElse(null);
        Set<Long> supportPlayerIds = userSupportPlayerRepository
                .findAllActiveWithPlayerAndTeam(userAccountId).stream()
                .map(supportPlayer -> supportPlayer.getPlayer().getId())
                .collect(Collectors.toSet());
        boolean hasPreference = supportTeamId != null || !supportPlayerIds.isEmpty();

        List<Quiz> ordered = quizzes.stream()
                .sorted(Comparator
                        .comparing((Quiz quiz) -> !isPreferred(quiz, supportTeamId, supportPlayerIds))
                        .thenComparingLong(quiz -> shuffleKey(userAccountId, quiz.getId()))
                        .thenComparing(Quiz::getId))
                .filter(quiz -> !preferredOnly || !hasPreference
                        || isPreferred(quiz, supportTeamId, supportPlayerIds))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        // 상한은 정렬·필터가 모두 끝난 목록을 앞에서 자르는 연산이다 — 그래야 "선호 먼저 + 사용자별
        // 고정 랜덤"이 그대로 유지되고, 같은 사용자·같은 세트라면 매번 같은 문제가 잘려 나간다.
        // 먼저 자르면 정렬 대상이 달라져 부분집합 안정성이 깨진다.
        List<Quiz> served = ordered.size() > maxTodayCount
                ? List.copyOf(ordered.subList(0, maxTodayCount))
                : ordered;

        // 선조회 결과의 차집합만 만든다 — 이미 행이 있는 문제는 어떤 필드도 건드리지 않는다.
        // (created_at·inning 을 덮어쓰면 시한이 뒤로 밀려 재호출이 곧 연장 수단이 된다.)
        // 그래서 같은 세트를 다시 받는 재호출은 쓰기 SQL 이 0 건이다.
        List<Quiz> missing = served.stream()
                .filter(quiz -> !rowExistingIds.contains(quiz.getId()))
                .toList();
        if (!missing.isEmpty()) {
            quizUserSubmitRepository.insertUnansweredRows(userAccountId, inningByQuizId(missing),
                    now);
        }

        List<Long> quizIds = served.stream().map(Quiz::getId).toList();
        Map<Long, List<QuizOption>> optionsByQuizId = quizOptionRepository
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIds).stream()
                .collect(Collectors.groupingBy(option -> option.getQuiz().getId()));
        return served.stream()
                .map(quiz -> QuizResponse.of(quiz,
                        optionsByQuizId.getOrDefault(quiz.getId(), List.of()),
                        isPreferred(quiz, supportTeamId, supportPlayerIds)))
                .toList();
    }

    /**
     * 새로 만들 행에 넣을 {@code 문제 → 이닝} 표. 이닝은 귀속 경기의 {@code games.current_inning} 이며,
     * 경기가 없거나 그 경기의 이닝이 비어 있으면 {@code null}(=미상)이다 — <b>미상이어도 행은
     * 만든다</b>. 이닝 값 축과 제출 자격 축은 별개이고, 원천(py-collector) 미구현으로 지금은 사실상
     * 전부 미상이다. 여기서 행을 건너뛰면 지금은 <b>모든 제출이 403</b>이 되어 서비스가 멈춘다.
     *
     * <p>{@code game}은 목록 조회의 {@code @EntityGraph}가 함께 실어 오므로 여기서 LAZY 초기화가
     * 일어나지 않는다(항목 수에 비례하는 쿼리 금지 + {@code open-in-view: false}). ⚠ <b>"지금 응답에
     * 안 쓰니 빼자"로 {@code game} 축이나 이 스냅샷을 정리하지 말 것</b> — 후속 "한 이닝에 한 세트"
     * 회차 제한이 이 두 가지를 그대로 전제한다(빼면 그쪽이 조회를 새로 늘려야 한다).
     *
     * <p>값이 {@code null} 일 수 있어 {@code Collectors.toMap} 대신 {@link LinkedHashMap}에 담는다
     * (INSERT 순서도 서빙 순서 그대로 유지된다).
     */
    private Map<Long, Integer> inningByQuizId(List<Quiz> served) {
        Map<Long, Integer> innings = new LinkedHashMap<>();
        for (Quiz quiz : served) {
            innings.put(quiz.getId(),
                    quiz.getGame() == null ? null : quiz.getGame().getCurrentInning());
        }
        return innings;
    }

    /**
     * 단건 상세. <b>미편성 풀({@code quizDate == null}) 문제는 미존재와 똑같이 404</b>다 — 편성 전
     * 문제의 존재가 새어 나가면 id 순회로 내일 이후 출제분을 미리 볼 수 있게 된다.
     *
     * <p>내가 이미 <b>답한</b> 문제면 내 선택·정오·정답을 함께 싣는다(복기 화면). 아니면 세 필드는
     * 응답 본문에서 <b>키 자체가 빠진다</b>({@link QuizDetailResponse} — 정답 유출 방지).
     *
     * <p><b>{@code submitted}의 기준은 행의 존재가 아니라 답의 존재다.</b> 행은 받는 순간 생기므로
     * 행 유무로 판정하면 아직 풀지도 않은 문제에 정답이 실린다(그리고 답이 없는 행에서
     * {@code getSubmitOption()} 을 역참조해 NPE 로 500 이 난다). 대신 미답 행은 {@code expired} 로
     * 구분한다 — FE 는 {@code (submitted, expired)} 조합으로 <b>진행 중(false,false) · 답함(true,*) ·
     * 시한 초과(false,true)</b> 세 상태를 읽는다.
     *
     * <p>좋아요({@code liked}·{@code likeCount})는 <b>답한 경우에만</b> 싣는다 — 좋아요 자체는 이제 받은
     * 문제에 전부 열려 있지만, 이 응답의 필드 계약은 이번 작업에서 넓히지 않는다(FE 계약 변경은
     * {@code expired} 하나로 묶는다). 미답 상세에서는 조회조차 하지 않는다.
     */
    @Transactional(readOnly = true)
    public QuizDetailResponse getQuiz(Long userAccountId, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        List<QuizOption> options = quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(quizId);
        LocalDateTime now = QuizSubmitWindow.now();
        return quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .map(submit -> submit.getSubmitOption() == null
                        ? QuizDetailResponse.unsubmitted(quiz, options,
                                QuizSubmitWindow.isExpired(submit.getCreatedAt(), now))
                        : QuizDetailResponse.submitted(quiz, options, submit,
                                quizLikeService.likeOf(userAccountId, quizId)))
                // 행이 아예 없으면(받은 적 없음) 진행 중과 같은 모양이다 — "지금 풀 수 있는가"는
                // 이 응답이 아니라 /today 목록이 답한다
                .orElseGet(() -> QuizDetailResponse.unsubmitted(quiz, options, false));
    }

    /**
     * {@code (userAccountId, quizId)} 에서 유도하는 정렬 키. 사용자마다 다른 순서를 주되 같은
     * 사용자에게는 항상 같은 순서를 준다.
     *
     * <p><b>왜 {@code Collections.shuffle} 이 아니라 문제 한 건 단위 키인가</b>: 이 목록은 이미 푼
     * 문제를 빼고 내려가므로 한 문제 풀 때마다 리스트 길이가 줄어든다. shuffle 은 길이가 바뀌면
     * 순열이 통째로 달라져 새로고침마다 남은 문제 순서가 전부 뒤집힌다. 키가 문제마다 독립이면
     * 집합이 줄어도 남은 문제들의 상대 순서는 그대로다.
     *
     * <p><b>왜 {@code java.util.Random}·{@code ThreadLocalRandom} 이 아닌가</b>: quiz 앱은 파드가
     * 여러 개라 어느 파드가 응답하든 같은 순서가 나와야 한다. 시드·난수 구현에 기대지 않는 순수
     * 산술(splitmix64 finalizer)이어야 그게 성립하고, 상태·DB·캐시도 필요 없다.
     *
     * <p>곱셈·시프트 믹싱은 인접한 id 나 인접한 계정이 비슷한 키를 받아 순서가 사실상 id ASC 로
     * 되돌아가는 것을 막는다.
     */
    private static long shuffleKey(long userAccountId, long quizId) {
        long mixed = userAccountId * 0x9E3779B97F4A7C15L + quizId;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    /** 응원팀이 문제의 대상·상대 구단이거나 대상 선수가 내 응원 선수면 선호 문제다. */
    private boolean isPreferred(Quiz quiz, Long supportTeamId, Set<Long> supportPlayerIds) {
        if (supportTeamId != null
                && ((quiz.getTeam() != null && supportTeamId.equals(quiz.getTeam().getId()))
                || (quiz.getOpponentTeam() != null
                        && supportTeamId.equals(quiz.getOpponentTeam().getId())))) {
            return true;
        }
        return quiz.getPlayer() != null && supportPlayerIds.contains(quiz.getPlayer().getId());
    }
}
