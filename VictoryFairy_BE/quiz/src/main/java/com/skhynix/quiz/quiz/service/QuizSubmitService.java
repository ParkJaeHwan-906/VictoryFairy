package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse.InningResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuizSubmitService {

    // ⚠ 경기 상태 판정은 game_statuses 의 id 가 아니라 name 문자열로 한다(QuizService 와 같은 규칙) —
    //   id 는 py-collector 가 만난 순서대로 부여돼 환경마다 다를 수 있어 리터럴을 박으면 조용히 틀린다.
    //   두 이름만 분기하고 나머지(FINISHED·DRAW·CANCELED 와 아직 없는 이름)는 전부 기본 경로로
    //   떨어뜨린다 — 상태 정의역은 앱 배포 없이 늘어날 수 있다(시드가 "UNION ALL 한 줄"로 예고).
    private static final String SCHEDULED = "SCHEDULED";
    private static final String IN_PROGRESS = "IN_PROGRESS";

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final UserAccountRepository userAccountRepository;
    private final GameRepository gameRepository;
    private final QuizLikeService quizLikeService;

    @Transactional
    public QuizSubmitResponse submit(Long userAccountId, Long quizId, int optionNo) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        // ⚠ 시한 기준 시각은 kstClock 이 아니다 — created_at 이 JVM 기본 존으로 찍히기 때문이다
        //   (QuizSubmitWindow javadoc). 아래 조건부 UPDATE 도 같은 값을 쓴다.
        LocalDateTime now = QuizSubmitWindow.now();
        QuizUserSubmit served = quizUserSubmitRepository
                .findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));
        if (served.getSubmitOption() == null
                && QuizSubmitWindow.isExpired(served.getCreatedAt(), now)) {
            throw new BusinessException(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED);
        }
        QuizOption option = quizOptionRepository
                .findFirstByQuiz_IdAndOptionOrderByIdAsc(quizId, optionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        boolean correct = Objects.equals(quiz.getAnswer(), optionNo);
        UserAccount account = userAccountRepository.findWithLockById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        // score 는 nullable(사람이 쓴 퀴즈) — 배점 없는 문제의 정답은 적립 0 으로 다룬다
        long earnedPoint = correct && quiz.getScore() != null ? Math.round(quiz.getScore()) : 0L;
        if (earnedPoint > 0) {
            account.addPoint(earnedPoint);
        }

        // 조건부 UPDATE 한 방이 원자적 판정이다 — 동시에 들어온 두 제출 중 하나만 1 을 받는다.
        // 시한 조건을 여기서도 거는 이유: 위 검사와 이 문장 사이에 시한이 지나는 경우까지 닫는다.
        // 0 이면 BusinessException(런타임)이 트랜잭션을 롤백시켜 위의 적립도 함께 되돌아간다.
        int filled = quizUserSubmitRepository.fillAnswer(userAccountId, quizId, option, correct,
                QuizSubmitWindow.earliestValidCreatedAt(now), now);
        if (filled == 0) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        return new QuizSubmitResponse(correct, quiz.getAnswer(), optionNo, earnedPoint,
                account.getPoint());
    }

    @Transactional(readOnly = true)
    public QuizSubmissionHistoryResponse getHistory(Long userAccountId, String gameId) {
        // 상태 이름까지 함께 실어 오는 조회(/today 와 공용) — 상태는 FK 값이 아니라 game_statuses 행의
        // 컬럼이라 LAZY 로 두면 판정 순간 SELECT 가 한 번 더 나간다.
        Game game = gameRepository.findWithStatusByNaverGameId(gameId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
        // 예정 경기는 이닝 컬럼을 아예 읽지 않고 상태만으로 접는다 — /today 가 IN_PROGRESS 에서만
        // 세트를 주므로 시작 전 경기에는 대상 행이 존재할 수 없고 앞으로 생길 수도 없다.
        if (SCHEDULED.equals(game.getGameStatus().getName())) {
            throw new BusinessException(ErrorCode.GAME_NOT_STARTED);
        }

        List<QuizUserSubmit> submits =
                quizUserSubmitRepository.findGameSubmissions(userAccountId, game.getId());
        int lastInning = enumeratedLastInning(game);
        // 경기 단위 접기: 범위가 1..8 로 계산됐어도 그 경기에 내 행이 0건이면 0/0 원소 8개가 아니라
        // 빈 배열이다. 판정 모집단은 "그 경기의 내 행" 전부이며 범위 안팎을 가리지 않는다.
        if (submits.isEmpty() || lastInning < 1) {
            return QuizSubmissionHistoryResponse.of(List.of());
        }

        // 범위 밖(진행 중인 현재 이닝 등)과 개정 이전의 inning IS NULL 행은 여기서 빠진다 — 목록과
        // 요약이 같은 모집단을 쓰도록 필터를 한 번만 건다.
        List<QuizUserSubmit> enumerated = submits.stream()
                .filter(submit -> submit.getInning() != null
                        && submit.getInning() >= 1 && submit.getInning() <= lastInning)
                .toList();
        List<Long> quizIds = enumerated.stream()
                .map(submit -> submit.getQuiz().getId())
                .distinct()
                .toList();
        Map<Long, List<QuizOption>> optionsByQuizId = quizIds.isEmpty()
                ? Map.of()
                : quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIds).stream()
                        .collect(Collectors.groupingBy(option -> option.getQuiz().getId()));
        Map<Long, QuizLikeResponse> likeByQuizId = quizLikeService.likesOf(userAccountId, quizIds);
        // 이닝 그룹핑은 메모리에서 한다(이닝마다 쿼리를 돌면 이닝 N+1). 조회가 id 오름차순이라
        // 그룹 안의 순서가 곧 받은 순서다.
        Map<Integer, List<QuizUserSubmit>> byInning = enumerated.stream()
                .collect(Collectors.groupingBy(QuizUserSubmit::getInning));

        LocalDateTime now = QuizSubmitWindow.now();
        List<InningResponse> innings = IntStream.rangeClosed(1, lastInning)
                .mapToObj(inning -> InningResponse.of(inning,
                        byInning.getOrDefault(inning, List.of()).stream()
                                .map(submit -> QuizSubmissionItemResponse.from(submit,
                                        optionsByQuizId.getOrDefault(submit.getQuiz().getId(),
                                                List.of()),
                                        // 좋아요 0인 문제는 집계에서 빠져 있을 수 있다 — 0으로 채운다
                                        likeByQuizId.getOrDefault(submit.getQuiz().getId(),
                                                QuizLikeResponse.none()),
                                        // 답한 항목은 시한을 따지지 않는다(항상 false) — 이미 소진했다
                                        submit.getSubmitOption() == null
                                                && QuizSubmitWindow.isExpired(
                                                        submit.getCreatedAt(), now)))
                                .toList()))
                .toList();
        return QuizSubmissionHistoryResponse.of(innings);
    }

    private int enumeratedLastInning(Game game) {
        if (IN_PROGRESS.equals(game.getGameStatus().getName())) {
            Integer currentInning = game.getCurrentInning();
            return currentInning == null ? 0 : currentInning - 1;
        }
        Integer lastInning = game.getLastInning();
        return lastInning == null ? 0 : lastInning;
    }
}
