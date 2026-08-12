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
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore;
import com.skhynix.quiz.quiz.store.QuizSubmissionTicketStore.Ticket;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 퀴즈 제출(서버 채점 + 포인트 적립)과 풀이 이력 조회.
 *
 * <p><b>채점은 이 서버 트랜잭션 안에서만 한다</b> — 정답({@code Quiz.answer})은 조회 응답
 * ({@code QuizResponse})에 싣지 않는 것이 계약이라, 판정 근거가 클라이언트로 나가는 순간이 없다.
 *
 * <p>중복 제출 차단은 이중이다: 선제 {@code existsBy} 검사(친절한 409)와
 * {@code uk_quiz_users_submit_account_quiz} UNIQUE(동시 요청 2건이 둘 다 검사를 통과하는 race 의
 * 최종 중재자). UNIQUE 위반도 실패가 아니라 "이미 제출됨"이므로 같은 409 로 접는다.
 *
 * <p><b>제출에는 자격이 필요하다</b> — {@code /today}로 그 문제를 받았다는 티켓
 * ({@link QuizSubmissionTicketStore})이 있어야 하고, 그 티켓의 값이 곧 기록할 이닝이다.
 */
@Service
@RequiredArgsConstructor
public class QuizSubmitService {

    private static final Logger log = LoggerFactory.getLogger(QuizSubmitService.class);

    private static final int PAGE_SIZE = 20;

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final UserAccountRepository userAccountRepository;
    private final QuizLikeService quizLikeService;
    private final QuizSubmissionTicketStore ticketStore;

    /**
     * 제출 → 자격 검사 → 채점 → 정답이면 포인트 적립 → 기록 저장.
     *
     * <p>미편성 문제({@code quizDate == null})는 행이 있어도 404 다 — 편성 전 문제의 존재 자체를
     * 밖에 알리지 않는다(400/403 으로 구분해 주면 "행은 있다"가 새어 나간다).
     *
     * <p><b>검사 순서 404 → 409 → 403 → 400 → 계정 락·적립 → 저장은 그 자체가 계약이다.</b>
     * 최초 제출이 티켓을 지우므로(아래 커밋 후 삭제) 재제출 시점엔 티켓이 없다 — 403(티켓) 검사를
     * 409(선제출)보다 앞에 두면 <b>재제출 응답이 409 에서 403 으로 조용히 바뀐다</b>. 순서를 바꾸지 말 것.
     *
     * <p>티켓이 없으면(=`/today` 미경유 또는 시한 8분 경과) 403 이고 <b>아무 흔적도 남기지 않는다</b> —
     * 제출 행·포인트·Redis 변경·계정 락 전부 없다. 그래야 그 문제가 미제출로 남아 다음
     * {@code /today}에 다시 실린다(그게 유일한 복구 경로다 — 재발급 API 도 유예도 없다).
     *
     * <p>이닝은 티켓의 값을 그대로 쓴다. <b>제출 시점에 {@code games}를 다시 읽지 않는다</b> —
     * 기록하려는 값은 "문제를 받아 푼 시점의 이닝"이라, 오래 붙들었다 낸 제출에 지금 이닝을 적으면
     * 사실이 아닌 값을 남기게 된다. 티켓 값이 미상이면 {@code inning}은 NULL 로 저장된다.
     *
     * <p>계정 행 잠금({@code findWithLockById})은 적립 유실 방지용이다 — 락 없는 두 트랜잭션이 같은
     * 잔액에서 각자 더하면 한쪽이 사라진다. 잠근 뒤 {@code addPoint}가 규약이다(뮤테이터 javadoc).
     * 검사들(404/409/403/400)을 락 앞에 두어, 실패할 요청이 계정 행을 잠그고 시작하지 않게 한다
     * (Redis 접근도 락 구간 밖에서 끝난다).
     */
    @Transactional
    public QuizSubmitResponse submit(Long userAccountId, Long quizId, int optionNo) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        if (quizUserSubmitRepository.existsByUserAccount_IdAndQuiz_Id(userAccountId, quizId)) {
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        // 조회 실패(Redis 장애)는 여기서 예외로 올라가 500 이 된다 — "티켓 없음"(403)으로 접으면
        // 장애 중인 사용자에게 "당신은 자격이 없다"고 잘못 알리게 된다. 장애와 자격 미달은 다른 사실이다.
        Ticket ticket = ticketStore.find(userAccountId, quizId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));
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
                    .inning(ticket.inning())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // existsBy 검사를 동시에 통과한 두 요청 중 진 쪽 — 시스템 오류(500)가 아니라 중복(409)이다.
            // BusinessException 도 런타임 예외라 트랜잭션이 롤백돼 위의 적립도 함께 되돌아간다.
            throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
        }
        deleteTicketAfterCommit(userAccountId, quizId);
        return new QuizSubmitResponse(correct, quiz.getAnswer(), optionNo, earnedPoint,
                account.getPoint());
    }

    /**
     * 쓴 티켓 정리를 <b>커밋 이후로 미룬다</b>. 커밋 전에 지우면 롤백된 제출(UNIQUE 위반 등)이 자격만
     * 태워, 다시 낼 수 있는 상태인데 403 이 된다 — 실패는 티켓을 소모하지 않는 것이 계약이다.
     *
     * <p>삭제 실패는 <b>삼킨다</b>. 제출 행은 이미 커밋돼 되돌릴 수단이 없어, 여기서 500 을 내면
     * "행은 저장됐는데 클라이언트는 실패로 안다"는 더 나쁜 상태가 된다(채팅의 커밋 후 SSE 발행
     * fire-and-forget 과 같은 판단). "Redis 실패는 500" 정책의 유일한 예외다.
     *
     * <p>남은 티켓은 최대 8분 뒤 만료되고, 그 사이의 재제출은 <b>여전히 409</b>다 — 티켓은 제출의
     * 필요조건일 뿐 충분조건이 아니며(409 검사가 앞선다), 동시 요청은 UNIQUE 가 중재한다. 즉 이
     * 삭제는 중복 방지 수단이 아니라 정리 작업이라 실패해도 정합성 결과가 없다.
     */
    private void deleteTicketAfterCommit(Long userAccountId, Long quizId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ticketStore.delete(userAccountId, quizId);
                } catch (RuntimeException e) {
                    log.warn("제출 티켓 삭제 실패 — TTL 만료에 맡긴다 (account={}, quiz={})",
                            userAccountId, quizId, e);
                }
            }
        });
    }

    /**
     * 풀이 이력 한 페이지 + 전체 요약. 페이지 크기는 서버 고정(채팅 이력과 같은 규약 — 클라이언트가
     * size 를 키워 전체 덤프하는 경로를 막는다).
     *
     * <p>정답 보기 텍스트는 페이지의 quizId 들을 모아 {@code quiz_id IN (...)} 한 방으로 받아 메모리에서
     * 매핑한다 — 행마다 단건 조회하면 그대로 N+1 이다({@code QuizOptionRepository} javadoc 의 2쿼리 방식).
     * 같은 번호 중복 행이 있으면 첫 행(id 최소 아님에 유의 — (quizId, option) 정렬의 첫 행)을 쓴다.
     *
     * <p>좋아요도 같은 quizId 묶음으로 한 번에 받는다 — 항목이 1건이든 20건이든 추가 쿼리는 2건
     * (집계 1 + 내 좋아요 1)으로 고정이다({@code QuizLikeService.likesOf}).
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

        Map<Long, QuizLikeResponse> likeByQuizId = quizLikeService.likesOf(userAccountId, quizIds);

        Page<QuizSubmissionItemResponse> items = submits.map(submit ->
                QuizSubmissionItemResponse.from(submit,
                        optionTextByQuizId
                                .getOrDefault(submit.getQuiz().getId(), Map.of())
                                .get(submit.getQuiz().getAnswer()),
                        // 좋아요가 0인 문제는 집계에서 빠져 있을 수 있다 — 없는 키는 0으로 채운다
                        likeByQuizId.getOrDefault(submit.getQuiz().getId(),
                                QuizLikeResponse.none())));

        long total = quizUserSubmitRepository.countByUserAccount_Id(userAccountId);
        long correctCount = quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(userAccountId);
        double accuracy = total == 0 ? 0.0 : (double) correctCount / total;
        return new QuizSubmissionHistoryResponse(
                new QuizSubmissionHistoryResponse.Summary(total, correctCount, accuracy),
                PageResponse.from(items));
    }
}
