package com.skhynix.quiz.quiz.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 제출(서버 채점 + 포인트 적립)과 풀이 이력 조회.
 *
 * <p><b>채점은 이 서버 트랜잭션 안에서만 한다</b> — 정답({@code Quiz.answer})은 조회 응답
 * ({@code QuizResponse})에 싣지 않는 것이 계약이라, 판정 근거가 클라이언트로 나가는 순간이 없다.
 *
 * <p>중복 제출 차단은 이중이다: 선제 {@code existsBy} 검사(친절한 409)와
 * {@code uk_quiz_users_submit_account_quiz} UNIQUE(동시 요청 2건이 둘 다 검사를 통과하는 race 의
 * 최종 중재자). UNIQUE 위반도 실패가 아니라 "이미 제출됨"이므로 같은 409 로 접는다.
 */
@Service
@RequiredArgsConstructor
public class QuizSubmitService {

    private static final int PAGE_SIZE = 20;

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final UserAccountRepository userAccountRepository;

    /**
     * 제출 → 채점 → 정답이면 포인트 적립 → 기록 저장.
     *
     * <p>미편성 문제({@code quizDate == null})는 행이 있어도 404 다 — 편성 전 문제의 존재 자체를
     * 밖에 알리지 않는다(400/403 으로 구분해 주면 "행은 있다"가 새어 나간다).
     *
     * <p>계정 행 잠금({@code findWithLockById})은 적립 유실 방지용이다 — 락 없는 두 트랜잭션이 같은
     * 잔액에서 각자 더하면 한쪽이 사라진다. 잠근 뒤 {@code addPoint}가 규약이다(뮤테이터 javadoc).
     * 검사들(404/409/400)을 락 앞에 두어, 실패할 요청이 계정 행을 잠그고 시작하지 않게 한다.
     */
    @Transactional
    public QuizSubmitResponse submit(Long userAccountId, Long quizId, int optionNo) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        if (quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(userAccountId, quizId)) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
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

        try {
            quizUserSubmitRepository.save(QuizUserSubmit.builder()
                    .userAccount(account)
                    .quiz(quiz)
                    .submitOption(option)
                    .isAnswer(correct)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // existsBy 검사를 동시에 통과한 두 요청 중 진 쪽 — 시스템 오류(500)가 아니라 중복(409)이다.
            // BusinessException 도 런타임 예외라 트랜잭션이 롤백돼 위의 적립도 함께 되돌아간다.
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        return new QuizSubmitResponse(correct, quiz.getAnswer(), optionNo, earnedPoint,
                account.getPoint());
    }

    /**
     * 풀이 이력 한 페이지 + 전체 요약. 페이지 크기는 서버 고정(채팅 이력과 같은 규약 — 클라이언트가
     * size 를 키워 전체 덤프하는 경로를 막는다).
     *
     * <p>정답 보기 텍스트는 페이지의 quizId 들을 모아 {@code quiz_id IN (...)} 한 방으로 받아 메모리에서
     * 매핑한다 — 행마다 단건 조회하면 그대로 N+1 이다({@code QuizOptionRepository} javadoc 의 2쿼리 방식).
     * 같은 번호 중복 행이 있으면 첫 행(id 최소 아님에 유의 — (quizId, option) 정렬의 첫 행)을 쓴다.
     */
    @Transactional(readOnly = true)
    public QuizSubmissionHistoryResponse getHistory(Long userAccountId, int page) {
        Page<QuizUserSubmit> submits = quizUserSubmitRepository
                .findHistoryByUserAccountId(userAccountId, PageRequest.of(page, PAGE_SIZE));

        List<Long> quizIds = submits.getContent().stream()
                .map(submit -> submit.getQuiz().getId())
                .distinct()
                .toList();
        Map<Long, Map<Integer, String>> optionTextByQuizId = quizIds.isEmpty()
                ? Map.of()
                : quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIds).stream()
                        .collect(Collectors.groupingBy(option -> option.getQuiz().getId(),
                                Collectors.toMap(QuizOption::getOption, QuizOption::getContents,
                                        (first, dup) -> first)));

        Page<QuizSubmissionItemResponse> items = submits.map(submit ->
                QuizSubmissionItemResponse.from(submit,
                        optionTextByQuizId
                                .getOrDefault(submit.getQuiz().getId(), Map.of())
                                .get(submit.getQuiz().getAnswer())));

        long total = quizUserSubmitRepository.countByUserAccount_Id(userAccountId);
        long correctCount = quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(userAccountId);
        double accuracy = total == 0 ? 0.0 : (double) correctCount / total;
        return new QuizSubmissionHistoryResponse(
                new QuizSubmissionHistoryResponse.Summary(total, correctCount, accuracy),
                PageResponse.from(items));
    }
}
