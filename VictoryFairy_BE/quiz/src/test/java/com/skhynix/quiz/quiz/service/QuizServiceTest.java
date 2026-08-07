package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizType;
import com.skhynix.domain.quiz.repository.QuizOptionRepository;
import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizService#getTodayQuizzes}를 리포지토리 목으로 단위 검증한다. DB·Spring 컨텍스트 없음.
 *
 * <p>'오늘'은 KST 고정 클록으로 결정되므로 {@code Clock.fixed}를 직접 주입한다(프로덕션은
 * {@code QuizIngestConfig.kstClock}). 보기 조회가 문제 수와 무관하게 <b>IN 한 방(2쿼리 방식)</b>인지,
 * 그룹핑이 문제별로 정확한지가 핵심 검증이다.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizOptionRepository quizOptionRepository;

    private QuizService quizService;

    @BeforeEach
    void setUp() {
        Clock fixedKst = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(12, 0), KST).toInstant(), KST);
        quizService = new QuizService(quizRepository, quizOptionRepository, fixedKst);
    }

    private Quiz quiz(Long id, String typeName, String content, String difficulty, Double score) {
        Quiz quiz = Quiz.builder()
                .quizType(QuizType.builder().name(typeName).build())
                .content(content)
                .answer(0)
                .quizDate(TODAY)
                .difficulty(difficulty)
                .score(score)
                .build();
        ReflectionTestUtils.setField(quiz, "id", id);
        return quiz;
    }

    private QuizOption option(Quiz quiz, int no, String text) {
        return QuizOption.builder().quiz(quiz).option(no).contents(text).build();
    }

    @Test
    @DisplayName("오늘 문제가 2건이면 보기를 IN 한 방으로 받아 문제별로 묶어 반환한다(2쿼리 방식)")
    void getTodayQuizzes_twoQuizzes_groupsOptionsPerQuizWithSingleInQuery() {
        Quiz oxQuiz = quiz(1L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0);
        Quiz multiQuiz = quiz(2L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY))
                .willReturn(List.of(oxQuiz, multiQuiz));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L)))
                .willReturn(List.of(
                        option(oxQuiz, 0, "O"), option(oxQuiz, 1, "X"),
                        option(multiQuiz, 0, "LG"), option(multiQuiz, 1, "한화"),
                        option(multiQuiz, 2, "삼성"), option(multiQuiz, 3, "KT")));

        List<QuizResponse> result = quizService.getTodayQuizzes();

        assertThat(result).hasSize(2);
        QuizResponse first = result.get(0);
        assertThat(first.id()).isEqualTo(1L);
        assertThat(first.type()).isEqualTo("O/X");
        assertThat(first.question()).isEqualTo("문동주는 한화 소속이다?");
        assertThat(first.difficulty()).isEqualTo("EASY");
        assertThat(first.point()).isEqualTo(10.0);
        assertThat(first.options())
                .extracting(QuizResponse.OptionResponse::no, QuizResponse.OptionResponse::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "O"),
                        org.assertj.core.groups.Tuple.tuple(1, "X"));
        QuizResponse second = result.get(1);
        assertThat(second.id()).isEqualTo(2L);
        assertThat(second.type()).isEqualTo("객관식");
        assertThat(second.options())
                .extracting(QuizResponse.OptionResponse::no)
                .containsExactly(0, 1, 2, 3);
        // 문제 수와 무관하게 보기 조회는 단 1회 — 문제마다 단건 조회하면 N+1 회귀다
        verify(quizOptionRepository).findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(1L, 2L));
    }

    @Test
    @DisplayName("오늘 출제분이 없으면 빈 리스트를 반환하고 보기 조회 쿼리를 아예 부르지 않는다")
    void getTodayQuizzes_emptyDay_returnsEmptyListWithoutOptionQuery() {
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes();

        assertThat(result).isEmpty();
        verifyNoInteractions(quizOptionRepository);
    }

    @Test
    @DisplayName("보기 행이 없는 문제는 빈 options로 응답한다(NPE 없이 getOrDefault 흡수 — 요구사항 미기재 경계)")
    void getTodayQuizzes_quizWithoutOptionRows_returnsEmptyOptions() {
        Quiz orphan = quiz(7L, "객관식", "보기가 아직 없는 문제", null, null);
        given(quizRepository.findAllByQuizDateOrderByIdAsc(TODAY)).willReturn(List.of(orphan));
        given(quizOptionRepository.findAllByQuiz_IdInOrderByQuizIdAscOptionAsc(List.of(7L)))
                .willReturn(List.of());

        List<QuizResponse> result = quizService.getTodayQuizzes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).options()).isEmpty();
        assertThat(result.get(0).point()).isNull();
        assertThat(result.get(0).difficulty()).isNull();
    }
}
