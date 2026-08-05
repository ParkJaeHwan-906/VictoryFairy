package com.skhynix.user.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code TeamService.getTeams()} 단위 테스트. 요구사항: {@code docs/requirements/user/team-list.md}
 * (USER-TM-1 ~ 9).
 *
 * <p>여기서 다루는 것: 리포지토리가 준 결과를 서비스가 재정렬하지 않고 그대로 흘려보내는지(USER-TM-3의
 * "애플리케이션 레벨 재정렬 금지" 제약), 전체 반환(USER-TM-4), 빈 목록 처리(USER-TM-8), 그리고 엔티티를
 * 그대로 직렬화하지 않고 {@code id}·{@code name}만 담은 DTO로 변환하는지(USER-TM-2의 서비스 계층 몫).
 *
 * <p>{@code Team}은 {@code id}에 setter가 없고({@code @GeneratedValue}) 빌더도 {@code name}·{@code code}만
 * 받으므로, 고정된 id로 단언하려면 {@link ReflectionTestUtils#setField}로 직접 채운다(다른 엔티티에도
 * 흔한 패턴).
 *
 * <p><b>USER-TM-3의 한계</b>: 실제 정렬은 MySQL 콜레이션이 수행하므로, 이 단위 테스트는 "서비스가
 * 리포지토리가 준 순서를 바꾸지 않는다"까지만 검증한다. 콜레이션이 실제로 기대 순서를 만드는지는 여기서
 * 검증할 수 없다(DB 통합 테스트 필요 — 미커버 영역으로 보고).
 */
@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @InjectMocks
    private TeamService teamService;

    private Team teamOf(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    @Test
    @DisplayName("[USER-TM-4] teams 테이블의 모든 행을 조건 없이 DTO로 변환해 반환한다")
    void getTeams_returnsAllRowsMappedToDto() {
        // given
        List<Team> stubbedOrder = List.of(
                teamOf(6L, "KIA", "HT"),
                teamOf(7L, "KT", "KT"),
                teamOf(8L, "LG", "LG"),
                teamOf(9L, "NC", "NC"),
                teamOf(10L, "SSG", "SK"),
                teamOf(1L, "두산", "OB"),
                teamOf(2L, "롯데", "LT"),
                teamOf(3L, "삼성", "SS"),
                teamOf(4L, "키움", "WO"),
                teamOf(5L, "한화", "HH"));
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(stubbedOrder);

        // when
        List<TeamResponse> result = teamService.getTeams();

        // then
        assertThat(result).hasSize(10);
        assertThat(result.get(0)).isEqualTo(new TeamResponse(6L, "KIA"));
        assertThat(result.get(9)).isEqualTo(new TeamResponse(5L, "한화"));
    }

    @Test
    @DisplayName("[USER-TM-8] teams 테이블에 행이 없으면 예외 없이 빈 리스트를 반환한다")
    void getTeams_noRows_returnsEmptyListWithoutException() {
        // given
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(Collections.emptyList());

        // when
        List<TeamResponse> result = teamService.getTeams();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-TM-3, 한계 있음] 리포지토리가 준 순서를 서비스가 재정렬하지 않고 그대로 흘려보낸다"
            + "(실제 콜레이션 정렬 자체는 DB 통합 테스트가 필요해 여기서 검증 불가)")
    void getTeams_doesNotReorderRepositoryResult() {
        // given: 실제 콜레이션 기대 순서와 무관하게 일부러 뒤섞은 순서를 스텁으로 준다
        List<Team> shuffledOrder = List.of(
                teamOf(5L, "한화", "HH"),
                teamOf(6L, "KIA", "HT"),
                teamOf(1L, "두산", "OB"));
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(shuffledOrder);

        // when
        List<TeamResponse> result = teamService.getTeams();

        // then: 스텁이 준 순서 그대로(가나다/콜레이션 순으로 앱이 다시 정렬하지 않음)
        assertThat(result)
                .extracting(TeamResponse::name)
                .containsExactly("한화", "KIA", "두산");
    }

    @Test
    @DisplayName("[USER-TM-2] 엔티티를 직접 노출하지 않고 id·name만 담은 TeamResponse로 변환한다"
            + "(code는 TeamResponse에 필드 자체가 없어 컴파일 타임에 노출 불가)")
    void getTeams_mapsOnlyIdAndNameFromEntity() {
        // given
        Team team = teamOf(42L, "KIA", "HT");
        given(teamRepository.findAllByOrderByNameAsc()).willReturn(List.of(team));

        // when
        List<TeamResponse> result = teamService.getTeams();

        // then
        assertThat(result).containsExactly(new TeamResponse(42L, "KIA"));
    }
}
