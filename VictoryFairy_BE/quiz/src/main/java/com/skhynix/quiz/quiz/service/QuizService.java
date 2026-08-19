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
import com.skhynix.quiz.quiz.vote.QuizVoteTally;
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

        // 응답에 실린 문제만 초기화한다 — 상한(20)에 잘려 나간 문제는 키조차 만들지 않는다(위 403·409·
        // 빈 목록 경로도 여기까지 오지 않는다). 카운트를 위한 게 아니라 "아무도 안 고른 보기도 0 으로
        // 존재하게" 만들려는 것이므로, 이 호출을 통째로 건너뛰어도 최종 집계값은 같다.
        // ⚠ Redis 는 이 트랜잭션에 참여하지 않는다 — 뒤에 롤백이 나면 값 0 짜리 필드만 남는데 그건
        //   무해하다(표를 왜곡하지 않는다). 제출 경로와 달리 커밋 이후로 미루지 않는 이유다.
        quizVoteTally.initialize(quizIds.stream()
                .filter(quizId -> !optionsByQuizId.getOrDefault(quizId, List.of()).isEmpty())
                .collect(Collectors.toMap(quizId -> quizId,
                        quizId -> optionsByQuizId.get(quizId).stream()
                                .map(QuizOption::getOption)
                                .toList())));

        return served.stream()
                .map(quiz -> QuizResponse.of(quiz,
                        optionsByQuizId.getOrDefault(quiz.getId(), List.of()),
                        isPreferred(quiz, supportTeamId, supportPlayerIds)))
                .toList();
    }

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

    private int servableInning(Game game) {
        Integer inning = game.getCurrentInning();
        if (inning == null) {
            throw new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE);
        }
        return inning;
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
}
