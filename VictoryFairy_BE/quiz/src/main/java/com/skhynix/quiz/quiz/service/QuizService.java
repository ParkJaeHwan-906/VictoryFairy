package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 조회. '오늘'은 KST 다({@code kstClock} — 파드 기본 존은 k8s Deployment env 의 {@code TZ}
 * 설정에 달려 있어, 그걸 그대로 쓰는 기본 클록으로는 자정~09시 어긋남을 코드로 보장할 수 없다).
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

    // ⚠ 경기 상태 판정은 game_statuses 의 id 가 아니라 이 name 문자열로 한다. id 는 py-collector 가
    //   만난 순서대로 부여돼 환경마다 다를 수 있어(infra/sql/game-statuses-init.sql), 리터럴 4 를
    //   코드에 박으면 다른 환경에서 조용히 틀린다.
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserSupportPlayerRepository userSupportPlayerRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final GameRepository gameRepository;
    private final QuizLikeService quizLikeService;
    private final Clock clock;
    private final int maxTodayCount;

    // 상한을 설정값으로 두는 이유: "우선 20 으로 하고 추후 조정" 이므로 조정에 배포가 필요하면
    // 안 된다. 편성 수(quiz.serve.daily-count)와는 별개 축이라 키를 재사용하지 않는다 —
    // 한 값에 묶으면 그날 세트 크기를 바꾸지 않고는 노출 개수를 못 바꾼다.
    public QuizService(QuizRepository quizRepository, QuizOptionRepository quizOptionRepository,
            UserSupportTeamRepository userSupportTeamRepository,
            UserSupportPlayerRepository userSupportPlayerRepository,
            QuizUserSubmitRepository quizUserSubmitRepository, GameRepository gameRepository,
            QuizLikeService quizLikeService,
            Clock clock, @Value("${quiz.serve.max-today-count:20}") int maxTodayCount) {
        this.quizRepository = quizRepository;
        this.quizOptionRepository = quizOptionRepository;
        this.userSupportTeamRepository = userSupportTeamRepository;
        this.userSupportPlayerRepository = userSupportPlayerRepository;
        this.quizUserSubmitRepository = quizUserSubmitRepository;
        this.gameRepository = gameRepository;
        this.quizLikeService = quizLikeService;
        this.clock = clock;
        this.maxTodayCount = maxTodayCount;
    }

    /**
     * 요청자가 지목한 경기({@code gameId} = {@code games.naver_game_id})가 <b>지금 문제를 줄 수 있는
     * 상태</b>일 때만 오늘(KST) 세트를 선호 먼저(그 안에서는 사용자별 랜덤) 정렬해 반환한다. 선호 =
     * 내 응원팀이 문제의 대상·상대 구단이거나, 문제의 대상 선수가 내 응원 선수인 것. 랜덤은 그룹
     * <b>안에서만</b> 일어나므로 선호 문제가 비선호보다 뒤로 밀리는 일은 없다.
     *
     * <p><b>세트를 줄지부터 가린다.</b> 검증 순서는 ① 경기 해석·검증 4종(존재·오늘·내 응원 구단·
     * {@code IN_PROGRESS}) ② 이닝 확보 ③ <b>그 {@code (경기, 이닝)} 에 이미 받았는가</b> 이고,
     * ①②는 403 {@code QUIZ_NOT_SERVABLE}, ③은 409 {@code QUIZ_ALREADY_SERVED_IN_INNING} 이다.
     * 통과한 뒤에야 "무엇을 줄지"(오늘 세트·선호 정렬·상한)를 따진다 — 둘은 별개 축이다.
     *
     * <p><b>이닝은 문제의 속성이 아니라 요청자의 관전 시점이다.</b> 그래서 한 요청으로 만들어지는 행은
     * 문제와 무관하게 전부 같은 {@code (game_id, inning)} 을 갖고, {@code quizzes.game_id}(문제가 다루는
     * 경기)는 이 판정에 <b>전혀 관여하지 않는다</b>. 집계하려는 것이 "이 문제가 몇 회짜리인가"가 아니라
     * "이 사용자가 자기 팀 경기의 몇 회에 풀었는가"이기 때문이다.
     *
     * <p><b>제외 기준은 "행이 있는가" 하나다 — 재조회가 없다.</b> 한 번 받은 문제는 답 여부·시한과
     * 무관하게 다시 실리지 않고, 답하지 않았다면 미제출=오답으로 확정된다. 새로고침·앱 복귀 한 번에
     * 그 이닝 세트를 잃는다는 뜻이며 <b>알고 수용한 결과</b>다(이미 받은 문제의 제출은 8분 안에
     * 그대로 가능하다 — FE 가 목록을 들고 있으면 잃는 것은 문제 본문 재수신뿐).
     *
     * <p>{@code preferredOnly=true}는 선호 문제만 남긴다. 단 <b>응원팀도 응원 선수도 없으면 필터
     * 기준 자체가 없으므로 전체를 반환</b>한다(no-op). 빈 배열은 이제 <b>"줄 수 있는데 줄 게 없다"</b>
     * (오늘 세트 없음 · 남은 문제를 이미 다 받음)만 뜻한다 — "지금은 줄 수 없다"는 전부 에러다.
     *
     * <p><b>응답에 실은 문제마다 미답 행을 만든다 — 그래서 이 조회는 쓰기 트랜잭션이다</b>
     * ({@code readOnly = true} 가 아니다). 그 행 하나가 네 역할을 겸한다: <b>존재는 제출 자격</b>
     * ({@code /today}를 거쳐 받았다), <b>{@code created_at} + 8분은 시한</b>, <b>{@code inning}은 받은
     * 시점의 이닝</b>, <b>{@code (game_id, inning)} 은 회차 판정 키</b>. 앱이 강제 종료돼도 "받았고 안
     * 냈다"는 사실이 이미 MySQL 에 있으므로 만료를 뒤늦게 알아낼 장치가 필요 없다.
     *
     * <p>행 생성이 실패하면 목록도 주지 않는다(같은 트랜잭션 — 부분 성공 금지). 행 없는 목록은 전부
     * 403 이 되어 "받았는데 못 내는" 상태가 되는데, 이제 <b>재조회로 복구할 수도 없다</b>. 상한에
     * 잘려 나간 문제에는 행을 만들지 않으므로 그 문제의 제출도 403 이다.
     */
    @Transactional
    public List<QuizResponse> getTodayQuizzes(Long userAccountId, String gameId,
            boolean preferredOnly) {
        // 응원 구단은 이제 "경기를 찾는 근거"가 아니라 "넘어온 경기를 검증하는 기준"이다. 조회는
        // 요청당 1회이고, 아래 선호 판정도 이 값을 그대로 재사용한다.
        Long supportTeamId = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(supportTeam -> supportTeam.getTeam().getId())
                .orElse(null);
        Game game = servableGame(gameId, supportTeamId);
        int inning = servableInning(game);
        // 한 이닝에 한 세트. 판정 키에 경기가 들어 있어 날짜 조건이 필요 없다(어제 9회는 game_id 가
        // 달라 오늘 9회를 막지 않는다). 다 풀었든 안 풀었든 "그 이닝에 받았다"는 사실은 같다.
        if (quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                userAccountId, game.getId(), inning)) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SERVED_IN_INNING);
        }

        List<Quiz> published = quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(clock));
        if (published.isEmpty()) {
            return List.of();
        }
        // 행이 있는 문제는 전부 제외한다 — 답 여부도 시한도 보지 않으므로 판정이 조회 시각에
        // 의존하지 않는다. 남은 문제는 정의상 행이 없으니 이 결과가 곧 INSERT 대상이기도 하다.
        Set<Long> servedIds = new HashSet<>(quizUserSubmitRepository.findServedQuizIds(
                userAccountId, published.stream().map(Quiz::getId).toList()));
        List<Quiz> quizzes = published.stream()
                .filter(quiz -> !servedIds.contains(quiz.getId()))
                .toList();
        if (quizzes.isEmpty()) {
            return List.of();
        }

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

        List<Long> quizIds = served.stream().map(Quiz::getId).toList();
        // 실을 문제는 전부 행이 없는 것들이라(위 제외 필터) 차집합을 다시 구할 필요가 없다. 왕복은
        // 문제 수와 무관하게 1회이고, 동시 요청이 같은 행을 만들려 해도 UNIQUE 가 중재해 기존 행의
        // created_at·inning 은 덮이지 않는다(시한이 뒤로 밀리지 않는다).
        // ⚠ servedAt 기준은 kstClock 이 아니다 — 비교 대상 created_at 이 JVM 기본 존으로 찍히므로
        //   시한 판정도 같은 존을 써야 한다(QuizSubmitWindow javadoc). kstClock 은 위 quiz_date 조회와
        //   '오늘 경기' 판정처럼 "며칠인가"에만 쓴다.
        quizUserSubmitRepository.insertUnansweredRows(userAccountId, quizIds, game.getId(), inning,
                QuizSubmitWindow.now());

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
     * 요청이 지목한 경기를 <b>기준 경기</b>로 해석한다 — 검증 4종(존재 · 오늘(KST) · 요청자의 응원
     * 구단이 뛰는 경기 · {@code IN_PROGRESS})을 통과했을 때만 그렇게 부른다. 하나라도 아니면
     * 403 {@code QUIZ_NOT_SERVABLE} 이고 사유를 구분해 주지 않는다.
     *
     * <p><b>이 네 검사는 편의 검증이 아니라 "이닝당 1회" 제한의 전제다.</b> {@code gameId} 가 클라이언트
     * 입력이라 회차 판정 키의 일부를 클라이언트가 쥐고 있다 — 하나라도 빠지면 지난 경기 id 나 남의 팀
     * 경기 id 로 갈아타는 것만으로 세트를 무제한 받을 수 있다.
     *
     * <p>조회는 <b>요청당 1회</b>다: 상태만 함께 실어 오는 {@code findWithStatusByNaverGameId} 한 번으로
     * 네 검사를 모두 판정한다(홈·원정 구단은 {@code games} 행의 FK 값이라 프록시 초기화가 없다).
     * 값이 없는 {@code gameId}(빈 문자열 포함)는 "찾을 수 없음"으로 흡수돼 같은 403 이 된다.
     *
     * <p>'오늘'은 {@code kstClock} 기준이고, {@code games.game_date} 가 {@code datetime(6)} 이라
     * <b>반개구간 {@code [오늘 00:00, 내일 00:00)}</b> 으로 판정한다(등치 비교·{@code Between} 금지 —
     * {@code GameRepository} 규약과 같은 이유).
     *
     * <p>홈·원정 <b>양쪽</b>을 본다 — 한쪽만 보면 원정 경기 날 전 사용자가 막힌다. 응원 구단이 아예
     * 없는 계정(정상 흐름에 없는 데이터 이상)은 어떤 경기도 통과할 수 없어 자연히 같은 403 이다.
     */
    private Game servableGame(String naverGameId, Long supportTeamId) {
        Game game = gameRepository.findWithStatusByNaverGameId(naverGameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE));

        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        LocalDateTime gameDate = game.getGameDate();
        if (gameDate.isBefore(todayStart) || !gameDate.isBefore(todayStart.plusDays(1))) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        if (supportTeamId == null
                || (!supportTeamId.equals(game.getHomeTeam().getId())
                        && !supportTeamId.equals(game.getAwayTeam().getId()))) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        // 상태는 이름으로 판정한다(위 IN_PROGRESS 상수 주석). 취소 경기도 여기서 함께 걸리므로
        // cancel_reason 을 읽는 별도 분기를 만들지 않는다.
        if (!IN_PROGRESS.equals(game.getGameStatus().getName())) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        return game;
    }

    /**
     * 기준 경기의 현재 이닝. 값이 없으면 403 {@code QUIZ_NOT_SERVABLE} 이다.
     *
     * <p><b>정상 흐름이 아니라 방어다</b> — {@code IN_PROGRESS} 와 {@code current_inning != null} 은 같이
     * 움직이지만 <b>그 불변식을 지키는 주체는 이 앱이 아니라 원천(py-collector)</b>이라, 남의 컴포넌트가
     * 지키는 규칙에 조용히 기대지 않는다. 막지 않으면 {@code inning IS NULL} 인 행이 쌓이는데
     * {@code NULL = ?} 는 참이 아니라 회차 판정이 그 행을 못 보고, <b>그 사용자만 이닝당 1회 제한을
     * 무제한 우회</b>하게 된다.
     *
     * <p>반대로 위 상태 검사를 "이닝이 비었으니 진행 중이 아니겠지"로 대신하지도 않는다 — 제품 규칙이
     * "경기가 진행 중일 때만"이라 안내의 근거는 상태 컬럼이어야 한다.
     */
    private int servableInning(Game game) {
        Integer inning = game.getCurrentInning();
        if (inning == null) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        return inning;
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
