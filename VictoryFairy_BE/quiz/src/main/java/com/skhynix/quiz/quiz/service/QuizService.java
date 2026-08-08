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
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

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
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizOptionRepository quizOptionRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserSupportPlayerRepository userSupportPlayerRepository;
    private final QuizUserSubmitRepository quizUserSubmitRepository;
    private final Clock clock;

    /**
     * 오늘(KST) 세트를 선호 먼저(그 안에서는 id ASC) 정렬해 반환한다. 선호 = 내 응원팀이 문제의
     * 대상·상대 구단이거나, 문제의 대상 선수가 내 응원 선수인 것.
     *
     * <p>{@code preferredOnly=true}는 선호 문제만 남긴다. 단 <b>응원팀도 응원 선수도 없으면 필터
     * 기준 자체가 없으므로 전체를 반환</b>한다(no-op) — 빈 배열을 주면 "오늘 퀴즈 없음"과 구분이
     * 안 되는데, 실제로는 취향을 아직 안 정했을 뿐이다.
     */
    @Transactional(readOnly = true)
    public List<QuizResponse> getTodayQuizzes(Long userAccountId, boolean preferredOnly) {
        List<Quiz> quizzes = quizRepository.findAllByQuizDateOrderByIdAsc(LocalDate.now(clock));
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

        // 리포지토리가 id ASC 로 주므로 안정 정렬(sorted)이 그룹 내 순서를 그대로 보존한다
        List<Quiz> ordered = quizzes.stream()
                .sorted(Comparator.comparing(quiz -> !isPreferred(quiz, supportTeamId, supportPlayerIds)))
                .filter(quiz -> !preferredOnly || !hasPreference
                        || isPreferred(quiz, supportTeamId, supportPlayerIds))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Long> quizIds = ordered.stream().map(Quiz::getId).toList();
        Map<Long, List<QuizOption>> optionsByQuizId = quizOptionRepository
                .findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(quizIds).stream()
                .collect(Collectors.groupingBy(option -> option.getQuiz().getId()));
        return ordered.stream()
                .map(quiz -> QuizResponse.of(quiz,
                        optionsByQuizId.getOrDefault(quiz.getId(), List.of()),
                        isPreferred(quiz, supportTeamId, supportPlayerIds)))
                .toList();
    }

    /**
     * 단건 상세. <b>미편성 풀({@code quizDate == null}) 문제는 미존재와 똑같이 404</b>다 — 편성 전
     * 문제의 존재가 새어 나가면 id 순회로 내일 이후 출제분을 미리 볼 수 있게 된다.
     *
     * <p>내가 이미 제출한 문제면 내 선택·정오·정답을 함께 싣는다(복기 화면). 미제출이면 세 필드는
     * 응답 본문에서 <b>키 자체가 빠진다</b>({@link QuizDetailResponse} — 정답 유출 방지).
     */
    @Transactional(readOnly = true)
    public QuizDetailResponse getQuiz(Long userAccountId, Long quizId) {
        Quiz quiz = quizRepository.findById(quizId)
                .filter(found -> found.getQuizDate() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
        List<QuizOption> options = quizOptionRepository.findAllByQuiz_IdOrderByOptionAsc(quizId);
        return quizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id(userAccountId, quizId)
                .map(submit -> QuizDetailResponse.submitted(quiz, options, submit))
                .orElseGet(() -> QuizDetailResponse.unsubmitted(quiz, options));
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
