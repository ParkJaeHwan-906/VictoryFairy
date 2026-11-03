package com.skhynix.quiz.quizroom.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.entity.QuizRoom;
import com.skhynix.domain.quizroom.entity.QuizRoomAnswer;
import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import com.skhynix.domain.quizroom.repository.QuizRoomAnswerRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizOptionRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomQuizRepository;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomOptionResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomResultResponse;
import com.skhynix.quiz.quizroom.dto.ResultQuizResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답 저장의 트랜잭션 단위. 분포 이벤트는 이 트랜잭션이 <b>커밋된 뒤</b> {@link QuizRoomAnswerService} 가 새로 읽어 보낸다 —
 * 트랜잭션 안에서 센 값은 동시에 커밋되는 다른 계정의 답을 못 봐 분포가 덜 세어진 채 나간다.
 *
 * <p>point·bq·{@code quiz_users_submit}·{@code quizzes_like}·{@code quiz:votes:*} 어느 것도 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
class QuizRoomAnswerRecorder {

    private final UserAccountRepository userAccountRepository;
    private final QuizRoomRepository roomRepository;
    private final QuizRoomQuizRepository quizRepository;
    private final QuizRoomQuizOptionRepository optionRepository;
    private final QuizRoomAnswerRepository answerRepository;
    private final QuizRoomService roomService;
    private final Clock kstClock;

    /**
     * 검사 순서 404(방) → 404(문항) → 400(보기) → 409(중복) 고정.
     *
     * <p>계정 행 락을 <b>트랜잭션 맨 앞</b>에서 잡는다. 같은 계정의 답을 줄 세우지 않으면, 마지막 두 문항을 동시에 낸 두 요청이
     * 서로의 답을 못 봐 둘 다 "아직 남았다"로 끝나고 결과가 어느 응답에도 실리지 않는다(재조회 API 가 없어 영영 못 본다).
     * 맨 앞이어야 하는 이유: InnoDB 일관 읽기 스냅샷은 첫 일반 SELECT 에서 정해지므로, 락보다 먼저 읽으면 락을 얻은 뒤에도
     * 앞 요청의 커밋을 못 본다.
     */
    @Transactional
    public AnswerResponse record(Long userAccountId, long roomId, long quizId, Integer optionNo) {
        UserAccount account = userAccountRepository.findWithLockById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        QuizRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));
        QuizRoomQuiz quiz = quizRepository.findByIdAndRoom_Id(quizId, roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        if (optionNo == null || !optionRepository.existsByQuiz_IdAndNo(quizId, optionNo)) {
            throw new BusinessException(ErrorCode.QUIZ_OPTION_NOT_FOUND);
        }
        if (answerRepository.existsByUserAccount_IdAndQuiz_Id(userAccountId, quizId)) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        try {
            answerRepository.saveAndFlush(QuizRoomAnswer.builder()
                    .userAccount(account)
                    .room(room)
                    .quiz(quiz)
                    .optionNo(optionNo)
                    .createdAt(LocalDateTime.now(kstClock))
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 계정 락으로 정상 경로에선 닿지 않는다 — UNIQUE 가 마지막 심판으로 남아 있을 때의 응답
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }

        long answered = answerRepository.countByUserAccount_IdAndRoom_Id(userAccountId, roomId);
        int total = room.getQuizCount();
        QuizRoomResultResponse result = answered >= total ? result(userAccountId, roomId, total) : null;
        return new AnswerResponse(answered, total, result);
    }

    private QuizRoomResultResponse result(Long userAccountId, long roomId, int total) {
        Map<Long, Integer> myAnswers = answerRepository.findAllByUserAccount_IdAndRoom_Id(userAccountId, roomId)
                .stream()
                .collect(Collectors.toMap(answer -> answer.getQuiz().getId(), QuizRoomAnswer::getOptionNo));
        Map<Long, List<QuizRoomOptionResponse>> options = roomService.optionsByQuiz(roomId);
        List<ResultQuizResponse> quizzes = quizRepository.findAllByRoom_IdOrderByIdAsc(roomId).stream()
                .map(quiz -> {
                    int myOption = myAnswers.get(quiz.getId());
                    return new ResultQuizResponse(quiz.getId(), quiz.getType().label(), quiz.getQuestion(),
                            options.getOrDefault(quiz.getId(), List.of()), myOption, quiz.getAnswer(),
                            myOption == quiz.getAnswer());
                })
                .toList();
        int correctCount = (int) quizzes.stream().filter(ResultQuizResponse::correct).count();
        return new QuizRoomResultResponse(correctCount, total, quizzes);
    }
}
