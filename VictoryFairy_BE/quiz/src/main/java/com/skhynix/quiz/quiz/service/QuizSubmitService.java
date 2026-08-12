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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
 * <p><b>제출은 행을 만드는 일이 아니라 이미 있는 행의 빈 답을 채우는 일이다.</b> 행은 {@code /today}가
 * 서빙하면서 미리 만들어 두고({@link QuizService}), 여기서는 <b>{@code submit_option_id IS NULL} 이면서
 * 시한이 남았을 때만</b> 갱신되는 조건부 UPDATE 를 한 방 날린다. 그 <b>영향 행 수 0 이 곧 중복 제출
 * 판정</b>이라, 선검사({@code existsBy}) + UNIQUE 위반 변환의 이중 구조가 통째로 사라졌다.
 *
 * <p><b>제출 자격의 근거는 DB 행이다</b>(Redis 티켓이 아니다) — 행이 없으면 {@code /today}를 거치지
 * 않았거나 상한에 잘려 못 받은 것이라 403 이고, 그 판정은 어느 파드가 받아도 같다. 부수 효과로 이
 * 경로는 <b>Redis 장애와 무관</b>해졌다.
 */
@Service
@RequiredArgsConstructor
public class QuizSubmitService {

    private static final int PAGE_SIZE = 20;

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final UserAccountRepository userAccountRepository;
    private final QuizLikeService quizLikeService;

    /**
     * 제출 → 자격·시한 검사 → 채점 → 정답이면 포인트 적립 → 미답 행에 답 채우기.
     *
     * <p>미편성 문제({@code quizDate == null})는 행이 있어도 404 다 — 편성 전 문제의 존재 자체를
     * 밖에 알리지 않는다(400/403 으로 구분해 주면 "행은 있다"가 새어 나간다).
     *
     * <p><b>검사 순서는 404 → 403 → 400 → 계정 락·적립 → 409 다.</b> 종전(…409 → 403…)과 달리
     * <b>409 가 맨 뒤</b>인데, 중복 판정이 선검사가 아니라 조건부 UPDATE 의 결과로만 나오기 때문이다.
     * ⚠ 관측 가능한 변화가 하나 있다 — <b>이미 답한 문제에 없는 보기 번호를 보내면 종전 409 였고 이제
     * 400 이다.</b>
     *
     * <p>403 의 뜻은 둘이다: <b>행이 없다</b>(=`/today` 미경유 또는 상한 절삭)와 <b>미답 행인데 시한
     * 8분이 지났다</b>. 응답은 상태코드·본문까지 동일해 구분되지 않는다(구분이 필요하면 상세 조회의
     * {@code (submitted, expired)}를 본다). 거절은 <b>아무 흔적도 남기지 않는다</b> — 제출 경로는 행을
     * 만들지 않고(만드는 곳은 {@code /today} 하나뿐), 포인트도 계정 락도 건드리지 않는다.
     * ⚠ <b>답한 행은 시한이 지났어도 403 이 아니다</b> — 그 자리를 403 으로 접으면 재제출 응답이
     * 8분 전후로 409 에서 403 으로 갈린다.
     *
     * <p>이닝은 <b>건드리지 않는다</b>. 서빙 시점에 이미 행에 찍혔고, 제출 처리는 {@code games}를 다시
     * 읽지 않는다 — 기록하려는 값이 "문제를 받아 푼 시점의 이닝"이라 오래 붙들었다 낸 제출에 지금
     * 이닝을 적으면 사실이 아닌 값을 남기게 된다.
     *
     * <p>계정 행 잠금({@code findWithLockById})은 적립 유실 방지용이다 — 락 없는 두 트랜잭션이 같은
     * 잔액에서 각자 더하면 한쪽이 사라진다. 잠근 뒤 {@code addPoint}가 규약이다(뮤테이터 javadoc).
     * 검사들(404/403/400)을 락 앞에 두어, 실패할 요청이 계정 행을 잠그고 시작하지 않게 한다. 뒤이은
     * 409 는 락을 잡은 뒤에 나지만, 예외가 트랜잭션을 롤백시켜 적립도 함께 되돌아간다.
     */
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
     *
     * <p><b>답 없는 행도 그대로 싣는다</b>(진행 중이거나 시한 초과). 감출 수 없는 이유는 요약이다 —
     * {@code total}이 그 행을 세는데 목록에서 빼면 총건수와 항목 수가 어긋난다. 그래서 요약 두 카운트도
     * 고치지 않는다: <b>내지 않으면 틀린 것</b>이라 미답 행이 분모에 들어가고 {@code is_answer=false}
     * 로 오답과 똑같이 집계되는 것이 의도다({@code /today} 직후 정확도가 0% 로 떨어졌다가 풀수록
     * 올라간다 — 수용된 결과다).
     */
    @Transactional(readOnly = true)
    public QuizSubmissionHistoryResponse getHistory(Long userAccountId, int page) {
        Page<QuizUserSubmit> submits = quizUserSubmitRepository
                .findHistoryByUserAccountId(userAccountId, PageRequest.of(page, PAGE_SIZE));
        LocalDateTime now = QuizSubmitWindow.now();

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
                                QuizLikeResponse.none()),
                        // 답한 항목은 시한을 따지지 않는다(항상 false) — 이미 소진했다
                        submit.getSubmitOption() == null
                                && QuizSubmitWindow.isExpired(submit.getCreatedAt(), now)));

        long total = quizUserSubmitRepository.countByUserAccount_Id(userAccountId);
        long correctCount = quizUserSubmitRepository.countByUserAccount_IdAndIsAnswerTrue(userAccountId);
        double accuracy = total == 0 ? 0.0 : (double) correctCount / total;
        return new QuizSubmissionHistoryResponse(
                new QuizSubmissionHistoryResponse.Summary(total, correctCount, accuracy),
                PageResponse.from(items));
    }
}
