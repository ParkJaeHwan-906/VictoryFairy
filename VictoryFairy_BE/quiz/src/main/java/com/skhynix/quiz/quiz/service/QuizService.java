package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
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
    private final QuizSubmissionTicketStore ticketStore;
    private final Clock clock;
    private final int maxTodayCount;

    // 상한을 설정값으로 두는 이유: "우선 20 으로 하고 추후 조정" 이므로 조정에 배포가 필요하면
    // 안 된다. 편성 수(quiz.serve.daily-count)와는 별개 축이라 키를 재사용하지 않는다 —
    // 한 값에 묶으면 그날 세트 크기를 바꾸지 않고는 노출 개수를 못 바꾼다.
    public QuizService(QuizRepository quizRepository, QuizOptionRepository quizOptionRepository,
            UserSupportTeamRepository userSupportTeamRepository,
            UserSupportPlayerRepository userSupportPlayerRepository,
            QuizUserSubmitRepository quizUserSubmitRepository, QuizLikeService quizLikeService,
            QuizSubmissionTicketStore ticketStore, Clock clock,
            @Value("${quiz.serve.max-today-count:20}") int maxTodayCount) {
        this.quizRepository = quizRepository;
        this.quizOptionRepository = quizOptionRepository;
        this.userSupportTeamRepository = userSupportTeamRepository;
        this.userSupportPlayerRepository = userSupportPlayerRepository;
        this.quizUserSubmitRepository = quizUserSubmitRepository;
        this.quizLikeService = quizLikeService;
        this.ticketStore = ticketStore;
        this.clock = clock;
        this.maxTodayCount = maxTodayCount;
    }

    /**
     * 오늘(KST) 세트를 선호 먼저(그 안에서는 사용자별 랜덤) 정렬해 반환한다. 선호 = 내 응원팀이
     * 문제의 대상·상대 구단이거나, 문제의 대상 선수가 내 응원 선수인 것. 랜덤은 그룹 <b>안에서만</b>
     * 일어나므로 선호 문제가 비선호보다 뒤로 밀리는 일은 없다.
     *
     * <p><b>이미 푼 문제는 노출하지 않는다(정책)</b> — 재제출은 어차피 409지만, 목록에 남겨두는
     * 것 자체가 정책 위반이라 서버가 거른다. 선호 조회보다 먼저 거르는 이유: 다 푼 사용자는 응원
     * 정보 조회 없이 빈 배열로 끝난다. 그래서 빈 배열은 "오늘 세트 없음"과 "오늘 다 품" 두 경우를
     * 모두 뜻한다 — 구분이 필요하면 풀이 이력 API 가 담당한다.
     *
     * <p>{@code preferredOnly=true}는 선호 문제만 남긴다. 단 <b>응원팀도 응원 선수도 없으면 필터
     * 기준 자체가 없으므로 전체를 반환</b>한다(no-op) — 빈 배열을 주면 위의 두 경우와 구분이
     * 안 되는데, 실제로는 취향을 아직 안 정했을 뿐이다.
     *
     * <p><b>응답에 실은 문제마다 제출 티켓을 발급한다</b>({@link QuizSubmissionTicketStore}) — 이
     * 목록이 곧 "지금부터 8분 안에 낼 수 있는 문제"의 정의이고, 여기서 잘려 나간 문제는 제출도
     * 403 이다. 발급이 실패하면 목록을 주지 않는다(예외 그대로 → 500): 티켓 없는 목록은 전부 403 이
     * 되어 "받았는데 못 내는" 상태가 되므로 부분 성공을 만들 이유가 없다.
     */
    @Transactional(readOnly = true)
    public List<QuizResponse> getTodayQuizzes(Long userAccountId, boolean preferredOnly) {
        List<Quiz> published = quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(clock));
        if (published.isEmpty()) {
            return List.of();
        }

        Set<Long> submittedIds = Set.copyOf(quizUserSubmitRepository.findSubmittedQuizIds(
                userAccountId, published.stream().map(Quiz::getId).toList()));
        List<Quiz> quizzes = published.stream()
                .filter(quiz -> !submittedIds.contains(quiz.getId()))
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
        ticketStore.issue(userAccountId, inningByQuizId(served));

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
     * 티켓에 실을 {@code 문제 → 이닝} 표. 이닝은 귀속 경기의 {@code games.current_inning} 이며,
     * 경기가 없거나 그 경기의 이닝이 비어 있으면 {@code null}(=미상)이다 — <b>미상이어도 티켓은
     * 발급된다</b>. 이닝 값 축과 제출 자격 축은 별개이고, 원천 미구현으로 지금은 사실상 전부 미상이다.
     *
     * <p>{@code game}은 목록 조회의 {@code @EntityGraph}가 함께 실어 오므로 여기서 LAZY 초기화가
     * 일어나지 않는다(항목 수에 비례하는 쿼리 금지 + {@code open-in-view: false}). 값이 {@code null}
     * 일 수 있어 {@code Collectors.toMap} 대신 {@link LinkedHashMap}에 담는다.
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
     * <p>내가 이미 제출한 문제면 내 선택·정오·정답을 함께 싣는다(복기 화면). 미제출이면 세 필드는
     * 응답 본문에서 <b>키 자체가 빠진다</b>({@link QuizDetailResponse} — 정답 유출 방지).
     *
     * <p>좋아요({@code liked}·{@code likeCount})도 제출한 경우에만 싣는다 — 좋아요 자체가 푼 문제에만
     * 허용되므로 미제출 상세에서는 조회조차 하지 않는다(키 부재와 쿼리 부재가 같은 분기에서 나온다).
     */
    @Transactional(readOnly = true)
    public QuizDetailResponse getQuiz(Long userAccountId, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        List<QuizOption> options = quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(quizId);
        return quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .map(submit -> QuizDetailResponse.submitted(quiz, options, submit,
                        quizLikeService.likeOf(userAccountId, quizId)))
                .orElseGet(() -> QuizDetailResponse.unsubmitted(quiz, options));
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
