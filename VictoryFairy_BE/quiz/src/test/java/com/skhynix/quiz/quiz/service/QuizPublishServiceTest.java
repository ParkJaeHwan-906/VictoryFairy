package com.skhynix.quiz.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.quiz.repository.QuizRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link QuizPublishService#publishDaily} 단위 검증. 쿼터 두 축(팀별·공통)이 서로 독립적으로
 * 부족분만 보충하고(no-op 경로 포함), 팀 쿼터가 {@code teams} 테이블을 그대로 순회한다는 계약을
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class QuizPublishServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final int DAILY_COUNT_COMMON = 10;
    private static final int DAILY_COUNT_PER_TEAM = 200;

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private TeamRepository teamRepository;

    private QuizPublishService publishService;

    private Team ob;
    private Team lg;

    @BeforeEach
    void setUp() {
        publishService = new QuizPublishService(quizRepository, teamRepository,
                DAILY_COUNT_COMMON, DAILY_COUNT_PER_TEAM);
        ob = team(1L, "OB", "두산");
        lg = team(2L, "LG", "LG");
    }

    private static Team team(Long id, String code, String name) {
        Team team = Team.builder().code(code).name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    @Test
    @DisplayName("한 팀만 목표 미달이고 나머지는 충분하면 그 팀만 풀에서 편성된다")
    void publishDaily_onlyOneTeamBelowTarget_publishesOnlyThatTeam() {
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(ob, lg));
        given(quizRepository.countByQuizDateAndTeamIsNull(TODAY)).willReturn((long) DAILY_COUNT_COMMON);
        given(quizRepository.countByQuizDateAndTeam(TODAY, ob.getId())).willReturn(120L);
        given(quizRepository.countByQuizDateAndTeam(TODAY, lg.getId()))
                .willReturn((long) DAILY_COUNT_PER_TEAM);
        given(quizRepository.publishFromPoolForTeam(TODAY, ob.getId(), 80)).willReturn(80);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isEqualTo(80);
        verify(quizRepository).publishFromPoolForTeam(TODAY, ob.getId(), 80);
        verify(quizRepository, never()).publishFromPoolForTeam(eq(TODAY), eq(lg.getId()), anyInt());
        verify(quizRepository, never()).publishCommonFromPool(any(), anyInt());
    }

    @Test
    @DisplayName("경기 묶음 문항처럼 양팀 쿼터에 겹쳐 세어져도 실제 편성 UPDATE는 각 팀 쿼리마다 한 번씩만 나간다")
    void publishDaily_matchupQuiz_isCountedForBothTeamsButStampedOncePerRepositoryCall() {
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(ob, lg));
        given(quizRepository.countByQuizDateAndTeamIsNull(TODAY)).willReturn((long) DAILY_COUNT_COMMON);
        // 두산 vs LG 맞대결 문항 1건이 이미 오늘 세트에 있어 양쪽 모두 199건으로 집계된다(중복 집계 의도)
        given(quizRepository.countByQuizDateAndTeam(TODAY, ob.getId())).willReturn(199L);
        given(quizRepository.countByQuizDateAndTeam(TODAY, lg.getId())).willReturn(199L);
        given(quizRepository.publishFromPoolForTeam(TODAY, ob.getId(), 1)).willReturn(1);
        given(quizRepository.publishFromPoolForTeam(TODAY, lg.getId(), 1)).willReturn(1);

        int published = publishService.publishDaily(TODAY);

        // 레포지토리 메서드 자체는 "quiz_date IS NULL" 가드로 멱등함을 QuizRepository 쪽에서
        // 보증한다 — 서비스 레벨에서는 팀별로 정확히 한 번씩 그 가드가 걸린 UPDATE를 보낸다는
        // 것만 검증한다(각 팀 쿼터 호출이 독립적으로 나가고, 서로의 호출 횟수에 영향을 주지 않음).
        assertThat(published).isEqualTo(2);
        verify(quizRepository).publishFromPoolForTeam(TODAY, ob.getId(), 1);
        verify(quizRepository).publishFromPoolForTeam(TODAY, lg.getId(), 1);
    }

    @Test
    @DisplayName("공통 쿼터는 팀별 로직과 분리돼 그대로 부족분만 편성한다")
    void publishDaily_commonQuota_isIndependentOfTeamQuotas() {
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(ob));
        given(quizRepository.countByQuizDateAndTeamIsNull(TODAY)).willReturn(4L);
        given(quizRepository.publishCommonFromPool(TODAY, 6)).willReturn(6);
        given(quizRepository.countByQuizDateAndTeam(TODAY, ob.getId()))
                .willReturn((long) DAILY_COUNT_PER_TEAM);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isEqualTo(6);
        verify(quizRepository).publishCommonFromPool(TODAY, 6);
        verify(quizRepository, never()).publishFromPoolForTeam(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("공통·팀 쿼터 모두 이미 충족이면 어떤 UPDATE도 보내지 않고 0을 반환한다(no-op)")
    void publishDaily_allQuotasSatisfied_isNoOp() {
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(ob, lg));
        given(quizRepository.countByQuizDateAndTeamIsNull(TODAY)).willReturn((long) DAILY_COUNT_COMMON);
        given(quizRepository.countByQuizDateAndTeam(TODAY, ob.getId()))
                .willReturn((long) DAILY_COUNT_PER_TEAM);
        given(quizRepository.countByQuizDateAndTeam(TODAY, lg.getId()))
                .willReturn((long) DAILY_COUNT_PER_TEAM + 5);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isZero();
        verify(quizRepository, never()).publishCommonFromPool(any(), anyInt());
        verify(quizRepository, never()).publishFromPoolForTeam(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("풀이 부족하면 팀별로 부족분보다 적게 편성되고 그 수가 그대로 반영된다(실패 아님)")
    void publishDaily_poolExhaustedForTeam_returnsActuallyPublishedCount() {
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(ob));
        given(quizRepository.countByQuizDateAndTeamIsNull(TODAY)).willReturn((long) DAILY_COUNT_COMMON);
        given(quizRepository.countByQuizDateAndTeam(TODAY, ob.getId())).willReturn(190L);
        given(quizRepository.publishFromPoolForTeam(TODAY, ob.getId(), 10)).willReturn(3);

        int published = publishService.publishDaily(TODAY);

        assertThat(published).isEqualTo(3);
    }
}
