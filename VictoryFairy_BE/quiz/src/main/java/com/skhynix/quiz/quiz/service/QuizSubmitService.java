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

/**
 * 퀴즈 제출(서버 채점 + 포인트 적립)과 <b>경기 하나의 이닝별 풀이 결산</b> 조회.
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
     * <b>지목한 경기 한 건</b>의 이닝별 결산 — 이닝 배열 + 이닝별 요약 + 전체 요약. 조회 단위가 계정이
     * 아니라 경기이고 순서 축이 페이지가 아니라 이닝이라, 페이징하지 않는다.
     *
     * <p><b>{@code /today} 의 제공 가능 검증(오늘·응원 구단·{@code IN_PROGRESS})을 복사하지 않는다.</b>
     * 이력은 끝난 경기를 보는 화면이라 그 관문을 두면 기능 자체가 성립하지 않고, 걷어내도 새는 것이
     * 없다 — 응답은 요청자 본인의 행만 담으므로 임의의 {@code gameId} 를 넣어도 자기 기록 0건이 나올
     * 뿐이다. 판정은 <b>404(경기 미존재) → 403(예정 경기) → 200</b> 세 단계뿐이다.
     *
     * <p><b>열거 범위의 1차 축은 경기 상태({@code game_statuses.name})이고, 이닝 값의 유무는 그 안에서만
     * 본다</b>({@link #enumeratedLastInning}). 값 유무를 1차 축으로 삼으면 "예정 경기"(값이 없는 것이
     * 정상)와 "종료됐는데 수집기가 아직 못 채운 경기"를 구분할 수 없어 403 의 근거가 사라진다.
     *
     * <p><b>접기 규칙은 두 단위에 서로 다르게 적용된다.</b> ① 상태로 범위를 계산하고 ② <b>그 경기의 내
     * 행이 0건이면 범위가 계산됐어도 배열을 통째로 접는다</b>(그릴 축이 없는 화면이다) ③ 접히지
     * 않았으면 범위의 모든 이닝을 원소로 내린다(받은 문제가 없는 이닝도 {@code 0/0} 으로 남긴다 — 배열
     * 길이가 곧 열거 이닝 수여야 FE 가 이닝 축을 고정으로 그린다).
     *
     * <p><b>열거 범위 밖 행은 목록에서도 요약에서도 빠진다</b> — 전체 요약을 별도 count 쿼리로 구하지
     * 않고 이닝 합계로 접는 이유가 이것이다(요약과 목록이 어긋나지 않는 것이 계약이다). 진행 중인
     * 이닝에 방금 푼 문제가 다음 이닝까지 안 보이는 것은 "완료된 이닝만 결산한다"의 귀결이다.
     *
     * <p>보기와 좋아요는 quizId 묶음으로 한 번에 받는다 — 항목이 1건이든 220건이든 추가 쿼리는 보기 1 +
     * 좋아요 2 로 고정이고, 이닝마다 도는 구현(이닝 N+1)은 금지다.
     *
     * <p><b>답 없는 행도 그대로 싣는다</b>(진행 중이거나 시한 초과) — 미답은 분모에 들어가
     * {@code is_answer=false} 로 오답과 똑같이 집계되는 것이 제품 결정이고(내지 않으면 틀린 것),
     * 그러면 목록에도 있어야 어긋나지 않는다.
     */
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

    /**
     * 열거할 마지막 이닝(1부터 이 값까지). <b>0 이하면 열거할 이닝이 없다</b>는 뜻이다.
     *
     * <p>진행 중이면 {@code current_inning - 1}(<b>현재 이닝 제외</b> — 완료된 이닝만 결산한다. 1회
     * 진행 중이면 자연히 0 이 되어 빈 배열이다), 그 밖의 상태는 {@code last_inning} 이다. <b>표에 없는
     * 상태 이름도 이쪽</b>인데, 모르는 상태를 "시작 전"으로 단정하면 이미 쌓인 기록을 사용자에게서
     * 감추게 되고 그 손해가 빈 배열보다 크기 때문이다.
     *
     * <p>⚠ <b>두 컬럼을 섞어 읽거나 한쪽이 비면 다른 쪽으로 대체하는 폴백은 금지다</b> — 같은 화면이
     * 경기마다 다른 규칙으로 그려진다. 읽어야 할 값이 NULL 이면 에러가 아니라 빈 배열이다: 두 컬럼의
     * 원천은 py-collector 라 이 앱이 통제하지 못하고, 수집 지연으로 값이 비는 순간 화면 전체를 못 그리게
     * 하는 것보다 골격이라도 그리게 하는 편이 낫다(값이 채워지면 재조회만으로 정상화된다).
     */
    private int enumeratedLastInning(Game game) {
        if (IN_PROGRESS.equals(game.getGameStatus().getName())) {
            Integer currentInning = game.getCurrentInning();
            return currentInning == null ? 0 : currentInning - 1;
        }
        Integer lastInning = game.getLastInning();
        return lastInning == null ? 0 : lastInning;
    }
}
