package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.quiz.repository.QuizRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link QuizPublishService#publishDaily} 단위 검증 — 부족분 계산과 "이미 충족이면 UPDATE 를
 * 아예 안 보낸다"는 no-op 경로가 핵심이다(편성은 보충일 뿐 재배치가 아니라는 계약).
 */
@ExtendWith(MockitoExtension.class)
class QuizPublishServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final int DAILY_COUNT = 10;

    @Mock
    private QuizRepository quizRepository;

    private QuizPublishService publishService;

    @BeforeEach
    void setUp() {
        publishService = new QuizPublishService(quizRepository, DAILY_COUNT);
    }

    @Test
    @DisplayName("오늘 세트가 목표에 못 미치면 부족분만큼만 풀에서 편성한다(4건 → 6건 요청)")
    void publishDaily_belowTarget_publishesExactlyTheDeficit() {
        given(quizRepository.countByQuizDate(TODAY)).willReturn(4L);
        given(quizRepository.publishFromPool(TODAY, 6)).willReturn(6);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isEqualTo(6);
        verify(quizRepository).publishFromPool(TODAY, 6);
    }

    @Test
    @DisplayName("이미 목표를 충족하면 UPDATE를 보내지 않고 0을 반환한다(no-op)")
    void publishDaily_alreadySatisfied_isNoOp() {
        given(quizRepository.countByQuizDate(TODAY)).willReturn(10L);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isZero();
        verify(quizRepository, never()).publishFromPool(any(), anyInt());
    }

    @Test
    @DisplayName("경기 문항 등으로 목표를 넘긴 날도 no-op이다(초과분을 되돌리지 않는다)")
    void publishDaily_overTarget_isNoOpWithoutRollback() {
        given(quizRepository.countByQuizDate(TODAY)).willReturn(13L);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isZero();
        verify(quizRepository, never()).publishFromPool(any(), anyInt());
    }

    @Test
    @DisplayName("풀이 부족하면 부족분보다 적게 편성되고 그 수가 그대로 반환된다(실패 아님)")
    void publishDaily_poolExhausted_returnsActuallyPublishedCount() {
        given(quizRepository.countByQuizDate(TODAY)).willReturn(4L);
        given(quizRepository.publishFromPool(TODAY, 6)).willReturn(2);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isEqualTo(2);
    }
}
