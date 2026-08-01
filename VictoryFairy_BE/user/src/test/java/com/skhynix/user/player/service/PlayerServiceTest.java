package com.skhynix.user.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.user.player.dto.PlayerResponse;
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
 * {@code PlayerService.getPlayers(Long)} 단위 테스트.
 * 요구사항: {@code docs/requirements/user/player-list.md}(USER-PL-1 ~ 12).
 *
 * <p>{@link com.skhynix.user.team.service.TeamService} 테스트와 같은 구조를 따르되, 이 서비스에만 있는
 * 분기(teamId 유무)를 추가로 다룬다.
 *
 * <p>여기서 다루는 것: teamId 유무에 따라 서로 다른 리포지토리 메서드를 고르는지(그리고 다른 쪽은
 * 호출하지 않는지), 리포지토리가 준 순서를 재정렬하지 않는지, 엔티티가 아니라 {@code id}·{@code name}만
 * 담은 DTO로 변환하는지, 빈 결과를 예외 없이 흘려보내는지.
 *
 * <p>{@code Player}는 {@code id}에 setter가 없고({@code @GeneratedValue}) 빌더가 {@code team}을 필수로
 * 받으므로, 고정 id로 단언하려면 {@link ReflectionTestUtils#setField}로 채운다({@code TeamServiceTest}와
 * 같은 패턴).
 *
 * <p><b>USER-PL-3/4/5의 한계</b>: 실제 정렬은 MySQL 콜레이션이, 실제 필터링은 {@code team_id} 조건이
 * DB에서 수행하므로 이 단위 테스트는 "서비스가 올바른 쿼리를 고르고 결과를 그대로 흘려보낸다"까지만
 * 검증한다(문서의 "미커버 영역"에 같은 내용을 기록해 두었다).
 */
@ExtendWith(MockitoExtension.class)
class PlayerServiceTest {

    @Mock
    private PlayerRepository playerRepository;

    @InjectMocks
    private PlayerService playerService;

    private static final Team KIA = teamOf(6L, "KIA", "HT");

    private static Team teamOf(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private Player playerOf(Long id, String name) {
        Player player = Player.builder()
                .team(KIA)
                .name(name)
                .average(0.312)
                .kboPlayerId("7" + id)
                .build();
        ReflectionTestUtils.setField(player, "id", id);
        return player;
    }

    @Test
    @DisplayName("[USER-PL-4] teamId가 없으면 전체 조회 쿼리를 쓰고 모든 행을 DTO로 변환해 반환한다")
    void getPlayers_withoutTeamId_returnsAllRowsMappedToDto() {
        // given
        given(playerRepository.findAllByOrderByNameAsc())
                .willReturn(List.of(playerOf(1L, "강백호"), playerOf(2L, "김도영"), playerOf(3L, "이정후")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null);

        // then
        assertThat(result).containsExactly(
                new PlayerResponse(1L, "강백호"),
                new PlayerResponse(2L, "김도영"),
                new PlayerResponse(3L, "이정후"));
    }

    @Test
    @DisplayName("[USER-PL-5] teamId가 있으면 구단 필터 쿼리에 그 id를 그대로 넘기고, 전체 조회 쿼리는 "
            + "호출하지 않는다")
    void getPlayers_withTeamId_usesFilteredQueryOnly() {
        // given
        given(playerRepository.findAllByTeam_IdOrderByNameAsc(6L))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(6L);

        // then
        assertThat(result).containsExactly(new PlayerResponse(2L, "김도영"));
        verify(playerRepository).findAllByTeam_IdOrderByNameAsc(6L);
        verifyNoMoreInteractions(playerRepository); // findAllByOrderByNameAsc는 타지 않았다
    }

    @Test
    @DisplayName("[USER-PL-6] 선수가 없는(또는 존재하지 않는) teamId로 조회하면 예외 없이 빈 리스트를 "
            + "반환한다")
    void getPlayers_unknownTeamId_returnsEmptyListWithoutException() {
        // given: 존재하지 않는 구단이라도 리포지토리는 빈 결과를 줄 뿐 예외를 던지지 않는다
        given(playerRepository.findAllByTeam_IdOrderByNameAsc(999L)).willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = playerService.getPlayers(999L);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-PL-11] players 테이블에 행이 없으면 예외 없이 빈 리스트를 반환한다")
    void getPlayers_noRows_returnsEmptyListWithoutException() {
        // given
        given(playerRepository.findAllByOrderByNameAsc()).willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = playerService.getPlayers(null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-PL-3, 한계 있음] 리포지토리가 준 순서를 서비스가 재정렬하지 않고 그대로 흘려보낸다"
            + "(실제 콜레이션 정렬 자체는 DB 통합 테스트가 필요해 여기서 검증 불가)")
    void getPlayers_doesNotReorderRepositoryResult() {
        // given: 이름 오름차순과 일부러 어긋난 순서를 스텁으로 준다
        given(playerRepository.findAllByOrderByNameAsc())
                .willReturn(List.of(playerOf(3L, "이정후"), playerOf(1L, "강백호"), playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null);

        // then
        assertThat(result)
                .extracting(PlayerResponse::name)
                .containsExactly("이정후", "강백호", "김도영");
    }

    @Test
    @DisplayName("[USER-PL-2] 엔티티를 직접 노출하지 않고 id·name만 담은 PlayerResponse로 변환한다"
            + "(kboPlayerId·average는 DTO에 필드 자체가 없어 컴파일 타임에 노출 불가)")
    void getPlayers_mapsOnlyIdAndNameFromEntity() {
        // given
        given(playerRepository.findAllByOrderByNameAsc()).willReturn(List.of(playerOf(42L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null);

        // then
        assertThat(result).containsExactly(new PlayerResponse(42L, "김도영"));
    }
}
