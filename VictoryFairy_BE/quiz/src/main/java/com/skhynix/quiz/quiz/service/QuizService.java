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
import com.skhynix.quiz.quiz.dto.QuizVoteCountResponse;
import com.skhynix.quiz.quiz.vote.QuizVoteTally;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final QuizVoteTally quizVoteTally;
    private final Clock clock;
    private final int maxTodayCount;

    public QuizService(QuizRepository quizRepository, QuizOptionRepository quizOptionRepository,
            UserSupportTeamRepository userSupportTeamRepository,
            UserSupportPlayerRepository userSupportPlayerRepository,
            QuizUserSubmitRepository quizUserSubmitRepository, GameRepository gameRepository,
            QuizLikeService quizLikeService, QuizVoteTally quizVoteTally,
            Clock clock, @Value("${quiz.serve.max-today-count:20}") int maxTodayCount) {
        this.quizRepository = quizRepository;
        this.quizOptionRepository = quizOptionRepository;
        this.userSupportTeamRepository = userSupportTeamRepository;
        this.userSupportPlayerRepository = userSupportPlayerRepository;
        this.quizUserSubmitRepository = quizUserSubmitRepository;
        this.gameRepository = gameRepository;
        this.quizLikeService = quizLikeService;
        this.quizVoteTally = quizVoteTally;
        this.clock = clock;
        this.maxTodayCount = maxTodayCount;
    }

    @Transactional
    public List<QuizResponse> getTodayQuizzes(Long userAccountId, String gameId,
            boolean preferredOnly) {
        // 응원 구단은 이제 "경기를 찾는 근거"도, "조회를 막는 근거"도 아니다(QUIZ-INN-87·109 폐기) —
        // 기준 경기를 "응원 구단 경기"/"무관한 경기"로 가르는 분류 기준과, 기존 선호(preferred)
        // 표시 판정 둘 다에 이 값을 그대로 재사용한다. 응원 구단이 없으면 null 이고, 아래 선택
        // 로직은 그 경우를 전부 "무관한 경기" 경로로 태운다(AC-GSS-1-3).
        Long supportTeamId = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(supportTeam -> supportTeam.getTeam().getId())
                .orElse(null);
        Game game = servableGame(gameId);
        int inning = servableInning(game);
        // 한 이닝에 한 세트. 판정 키에 경기가 들어 있어 날짜 조건이 필요 없다(어제 9회는 game_id 가
        // 달라 오늘 9회를 막지 않는다). 다 풀었든 안 풀었든 "그 이닝에 받았다"는 사실은 같다.
        if (quizUserSubmitRepository.existsByUserAccount_IdAndGame_IdAndInning(
                userAccountId, game.getId(), inning)) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SERVED_IN_INNING);
        }

        // preferredOnly 는 하위 호환을 위해 파라미터만 남기고 결과에는 영향을 주지 않는다
        // (QUIZ-GSS-13) — 아래 경기 기반 캐스케이드가 그 역할을 완전히 대체한다.
        List<Quiz> served = selectQuizzes(userAccountId, game, supportTeamId);
        if (served.isEmpty()) {
            return List.of();
        }

        Set<Long> supportPlayerIds = userSupportPlayerRepository
                .findAllActiveWithPlayerAndTeam(userAccountId).stream()
                .map(supportPlayer -> supportPlayer.getPlayer().getId())
                .collect(Collectors.toSet());

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

        // 응답에 실린 문제만 초기화하고, 그 결과를 같은 호출에서 읽는다 — 상한(20)에 잘려 나간 문제는
        // 키조차 만들지 않는다(위 403·409·빈 목록 경로도 여기까지 오지 않는다). 초기화와 읽기를 한
        // 메서드로 묶은 이유는 순서가 계약이기 때문이다: 초기화가 끝난 뒤 읽어야 "아무도 안 고른 보기도
        // 0"이 성립하는데, 뒤집혀도 첫 서빙에서는 값이 우연히 같아 조용히 통과한다(QuizVoteTally javadoc).
        // ⚠ Redis 는 이 트랜잭션에 참여하지 않는다 — 뒤에 롤백이 나면 값 0 짜리 필드만 남는데 그건
        //   무해하다(표를 왜곡하지 않는다). 제출 경로와 달리 커밋 이후로 미루지 않는 이유다.
        // 실패하면 빈 맵이 오고, 없는 값은 아래에서 0 으로 채워진다(응답 스키마는 늘 한 모양).
        Map<Long, Map<Integer, Long>> voteCounts = quizVoteTally.initializeAndRead(quizIds.stream()
                .filter(quizId -> !optionsByQuizId.getOrDefault(quizId, List.of()).isEmpty())
                .collect(Collectors.toMap(quizId -> quizId,
                        quizId -> optionsByQuizId.get(quizId).stream()
                                .map(QuizOption::getOption)
                                .toList())));

        return served.stream()
                .map(quiz -> QuizResponse.of(quiz,
                        optionsByQuizId.getOrDefault(quiz.getId(), List.of()),
                        isPreferred(quiz, supportTeamId, supportPlayerIds),
                        voteCounts.getOrDefault(quiz.getId(), Map.of())))
                .toList();
    }

    // ⚠ 응원 구단 참여 검증(QUIZ-INN-109)은 폐기됐다 — 계약
    // docs/requirements/quiz/game-scoped-selection.md 상단 "폐기 대장" 참고. 응원 구단과 무관한
    // 오늘 IN_PROGRESS 경기도 더 이상 여기서 거절하지 않는다(QUIZ-GSS-2). 존재·오늘(KST)·IN_PROGRESS
    // 검증(QUIZ-INN-106~108·110·111·86·90·98)은 그대로 유지한다.
    private Game servableGame(String naverGameId) {
        Game game = gameRepository.findWithStatusByNaverGameId(naverGameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE));

        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        LocalDateTime gameDate = game.getGameDate();
        if (gameDate.isBefore(todayStart) || !gameDate.isBefore(todayStart.plusDays(1))) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        // 상태는 이름으로 판정한다(위 IN_PROGRESS 상수 주석). 취소 경기도 여기서 함께 걸리므로
        // cancel_reason 을 읽는 별도 분기를 만들지 않는다.
        if (!IN_PROGRESS.equals(game.getGameStatus().getName())) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        return game;
    }

    private int servableInning(Game game) {
        Integer inning = game.getCurrentInning();
        if (inning == null) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        return inning;
    }

    /**
     * 경기 기반 선택(계약 {@code docs/requirements/quiz/game-scoped-selection.md}) — 기준 경기를
     * "응원 구단 경기"/"무관한 경기"로 분류한 뒤(QUIZ-GSS-1) 그에 맞는 캐스케이드로 목표 수
     * ({@link #maxTodayCount})까지 채운다. 각 단계는 앞 단계가 이미 목표 수를 채웠으면 후보 조회
     * 자체를 하지 않는다(QUIZ-GSS-15-2) — {@link #fillStage}가 그 단락(short-circuit)을 담당한다.
     */
    private List<Quiz> selectQuizzes(Long userAccountId, Game game, Long supportTeamId) {
        LocalDate today = LocalDate.now(clock);
        long homeTeamId = game.getHomeTeam().getId();
        long awayTeamId = game.getAwayTeam().getId();
        // AC-GSS-1-1~1-3: 응원 구단이 없거나 기준 경기의 홈·어웨이 어느 쪽과도 다르면 무관한 경기다.
        boolean isSupportGame = supportTeamId != null
                && (supportTeamId == homeTeamId || supportTeamId == awayTeamId);

        List<Quiz> selected = new ArrayList<>();
        if (isSupportGame) {
            long opponentTeamId = supportTeamId == homeTeamId ? awayTeamId : homeTeamId;
            // C절 1~4단계: 오늘·선호팀 → 다른 날짜·선호팀 → 오늘·상대팀 → 다른 날짜·상대팀
            fillStage(selected, userAccountId,
                    () -> quizRepository.findByQuizDateAndClassifierTeam(today, supportTeamId));
            fillStage(selected, userAccountId,
                    () -> quizRepository.findByOtherQuizDateAndClassifierTeam(today, supportTeamId));
            fillStage(selected, userAccountId,
                    () -> quizRepository.findByQuizDateAndClassifierTeam(today, opponentTeamId));
            fillStage(selected, userAccountId,
                    () -> quizRepository.findByOtherQuizDateAndClassifierTeam(today, opponentTeamId));
            // C절 5단계(바닥, QUIZ-GSS-20) — 오늘+다른 날짜 통틀어 일반 퀴즈
            fillStage(selected, userAccountId, quizRepository::findGeneralQuizzesWithQuizDate);
        } else {
            List<Long> teamIds = List.of(homeTeamId, awayTeamId);
            // D절: 오늘 날짜 한정, 양팀 소속 무가중치 랜덤(QUIZ-GSS-8)
            fillStage(selected, userAccountId,
                    () -> quizRepository.findByQuizDateAndClassifierTeamIn(today, teamIds));
            // D절 바닥(QUIZ-GSS-20) — 오늘 세트 한정 일반 퀴즈
            fillStage(selected, userAccountId, () -> quizRepository.findGeneralQuizzesByQuizDate(today));
        }
        return selected;
    }

    /**
     * 캐스케이드 한 단계 — 후보를 받아 {@code selected}에 부족분만 채운다. 제외 규칙은
     * QUIZ-GSS-3-1(이미 받은 문제는 날짜·단계 무관 전부 제외)이고, 채우는 순서는 QUIZ-GSS-9(사용자별
     * 고정 시드)다. 목표 수를 이미 채웠으면 {@code candidateSupplier}를 아예 호출하지 않는다 —
     * {@code Supplier}로 지연시켜 "후보 조회조차 하지 않는다"(AC-GSS-15-2)는 계약을 지킨다.
     */
    private void fillStage(List<Quiz> selected, Long userAccountId,
            Supplier<List<Quiz>> candidateSupplier) {
        int remaining = maxTodayCount - selected.size();
        if (remaining <= 0) {
            return;
        }
        List<Quiz> candidates = candidateSupplier.get();
        if (candidates.isEmpty()) {
            return;
        }
        // 행이 있는 문제는 전부 제외한다 — 답 여부도 시한도 보지 않으므로 판정이 조회 시각에
        // 의존하지 않는다(QUIZ-GSS-3-1, "다른 날짜" 후보에도 동일하게 적용).
        Set<Long> servedIds = new HashSet<>(quizUserSubmitRepository.findServedQuizIds(
                userAccountId, candidates.stream().map(Quiz::getId).toList()));
        List<Quiz> ranked = candidates.stream()
                .filter(quiz -> !servedIds.contains(quiz.getId()))
                .sorted(Comparator
                        .comparingLong((Quiz quiz) -> shuffleKey(userAccountId, quiz.getId()))
                        .thenComparing(Quiz::getId))
                .toList();
        int take = Math.min(remaining, ranked.size());
        selected.addAll(ranked.subList(0, take));
    }

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

    private static long shuffleKey(long userAccountId, long quizId) {
        long mixed = userAccountId * 0x9E3779B97F4A7C15L + quizId;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    private boolean isPreferred(Quiz quiz, Long supportTeamId, Set<Long> supportPlayerIds) {
        if (supportTeamId != null
                && ((quiz.getTeam() != null && supportTeamId.equals(quiz.getTeam().getId()))
                || (quiz.getOpponentTeam() != null
                        && supportTeamId.equals(quiz.getOpponentTeam().getId())))) {
            return true;
        }
        return quiz.getPlayer() != null && supportPlayerIds.contains(quiz.getPlayer().getId());
    }

    /**
     * 아직 답하지 않은 문제의 보기별 투표 분포. 화면이 열려 있는 동안 <b>주기적으로 다시 부르는</b>
     * 경로라, 여기서 하는 일은 판정 1 + 보기 조회 1 + Redis 왕복 1 로 묶여 있다.
     *
     * <p><b>"받았고 아직 답하지 않은" 행이 있을 때만 값을 준다.</b> 그 외(받은 적 없음 · 이미 제출함 ·
     * 문제가 없음)는 예외가 아니라 {@code null} 이고, 컨트롤러가 {@code data: null} 로 내보낸다 —
     * 이미 낸 사람에게 분포를 감추는 것이 목적이라 404·403 으로 갈라 주면 <b>응답 코드만 보고
     * "그 문제를 받았는지"를 알아낼 수 있다</b>(QUIZ_LIKE_NOT_ALLOWED 와 같은 계열의 은닉).
     *
     * <p>⚠ 시한(+8분) 초과는 여기서 걸러 내지 않는다. 시한이 지난 미답 행은 제출 경로에서 403 이지만
     * 분포를 못 볼 이유는 없고, 시각으로 갈리는 판정을 여기에 하나 더 두면 같은 화면이 폴링 도중
     * 조용히 빈 응답으로 바뀐다.
     */
    @Transactional(readOnly = true)
    public QuizVoteCountResponse getQuizVoteCount(Long userAccountId, Long quizId) {
        // submit_option_id 는 이 행에 있는 FK 컬럼이라, LAZY 연관이어도 null 검사에 추가 조회가 없다.
        // (getQuiz 가 쓰는 것과 같은 판정 — 행 존재 = "받았다", 값 존재 = "답했다")
        boolean unanswered = quizUserSubmitRepository
                .findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .filter(submit -> submit.getSubmitOption() == null)
                .isPresent();
        if (!unanswered) {
            return null;
        }

        // Redis 에 없는 보기를 0 으로 채우는 근거가 이 목록이라, 표가 하나도 없거나 Redis 가 죽어도
        // 응답에 실리는 보기 개수는 늘 같다. 보기 텍스트도 여기서 나온다(/today 와 같은 항목 모양).
        List<QuizOption> options = quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(quizId);
        if (options.isEmpty()) {
            return null;
        }
        return QuizVoteCountResponse.of(quizId, options, quizVoteTally.read(quizId));
    }
}
