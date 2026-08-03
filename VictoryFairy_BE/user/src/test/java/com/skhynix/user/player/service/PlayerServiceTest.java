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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code PlayerService.getPlayers(Long, String)} 단위 테스트.
 * 요구사항: {@code docs/requirements/user/player-list.md}(USER-PL-1 ~ 16).
 *
 * <p>{@link com.skhynix.user.team.service.TeamService} 테스트와 같은 구조를 따르되, 이 서비스에만 있는
 * 분기(teamId·name 유무의 2×2 조합)를 추가로 다룬다.
 *
 * <p>여기서 다루는 것: teamId·name 유무 조합에 따라 서로 다른 리포지토리 메서드를 고르는지(그리고 다른
 * 쪽은 호출하지 않는지), 빈 문자열·공백 검색어를 "검색어 없음"으로 접는지, 리포지토리가 준 순서를
 * 재정렬하지 않는지, 엔티티가 아니라 {@code id}·{@code name}만 담은 DTO로 변환하는지, 빈 결과를 예외
 * 없이 흘려보내는지.
 *
 * <p>{@code Player}는 {@code id}에 setter가 없고({@code @GeneratedValue}) 빌더가 {@code team}을 필수로
 * 받으므로, 고정 id로 단언하려면 {@link ReflectionTestUtils#setField}로 채운다({@code TeamServiceTest}와
 * 같은 패턴).
 *
 * <p><b>USER-PL-3/4/5/13/14의 한계</b>: 실제 정렬은 MySQL 콜레이션이, 실제 필터링은 {@code team_id}
 * 조건과 {@code LIKE '%name%'} 이 DB에서 수행하므로 이 단위 테스트는 "서비스가 올바른 쿼리를 고르고
 * 결과를 그대로 흘려보낸다"까지만 검증한다(문서의 "미커버 영역"에 같은 내용을 기록해 두었다).
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
                .naverPcode("6" + id)
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
        List<PlayerResponse> result = playerService.getPlayers(null, null);

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
        List<PlayerResponse> result = playerService.getPlayers(6L, null);

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
        List<PlayerResponse> result = playerService.getPlayers(999L, null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-PL-11] players 테이블에 행이 없으면 예외 없이 빈 리스트를 반환한다")
    void getPlayers_noRows_returnsEmptyListWithoutException() {
        // given
        given(playerRepository.findAllByOrderByNameAsc()).willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, null);

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
        List<PlayerResponse> result = playerService.getPlayers(null, null);

        // then
        assertThat(result)
                .extracting(PlayerResponse::name)
                .containsExactly("이정후", "강백호", "김도영");
    }

    @Test
    @DisplayName("[USER-PL-2] 엔티티를 직접 노출하지 않고 id·name만 담은 PlayerResponse로 변환한다"
            + "(naverPcode·kboPlayerId·average는 DTO에 필드 자체가 없어 컴파일 타임에 노출 불가)")
    void getPlayers_mapsOnlyIdAndNameFromEntity() {
        // given
        given(playerRepository.findAllByOrderByNameAsc()).willReturn(List.of(playerOf(42L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, null);

        // then
        assertThat(result).containsExactly(new PlayerResponse(42L, "김도영"));
    }

    @Test
    @DisplayName("[USER-PL-13] name만 있으면 이름 부분일치 쿼리에 검색어를 그대로 넘기고, 전체 조회 쿼리는 "
            + "호출하지 않는다")
    void getPlayers_withNameOnly_usesNameSearchQueryOnly() {
        // given
        given(playerRepository.findAllByNameContainingOrderByNameAsc("도영"))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, "도영");

        // then
        assertThat(result).containsExactly(new PlayerResponse(2L, "김도영"));
        verify(playerRepository).findAllByNameContainingOrderByNameAsc("도영");
        verifyNoMoreInteractions(playerRepository); // findAllByOrderByNameAsc는 타지 않았다
    }

    @Test
    @DisplayName("[USER-PL-13] 이름 앞부분이 아니라 중간 일치도 검색어로 그대로 전달한다"
            + "(prefix가 아니라 contains 계약)")
    void getPlayers_withInfixName_passesKeywordAsIs() {
        // given: "도영"은 "김도영"의 앞부분이 아니다 — prefix 검색이었다면 매칭될 수 없는 검색어
        given(playerRepository.findAllByNameContainingOrderByNameAsc("도"))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, "도");

        // then
        assertThat(result).extracting(PlayerResponse::name).containsExactly("김도영");
    }

    @Test
    @DisplayName("[USER-PL-14] teamId와 name이 함께 오면 두 조건을 합친 단일 쿼리를 쓰고, 한쪽만 거는 "
            + "쿼리는 호출하지 않는다")
    void getPlayers_withTeamIdAndName_usesCombinedQueryOnly() {
        // given
        given(playerRepository.findAllByTeam_IdAndNameContainingOrderByNameAsc(6L, "도영"))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(6L, "도영");

        // then
        assertThat(result).containsExactly(new PlayerResponse(2L, "김도영"));
        verify(playerRepository).findAllByTeam_IdAndNameContainingOrderByNameAsc(6L, "도영");
        // 앱에서 두 결과의 교집합을 내지 않는다 — 단일 쿼리로 DB가 좁힌다
        verifyNoMoreInteractions(playerRepository);
    }

    @ParameterizedTest(name = "name=\"{0}\"")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("[USER-PL-15] 빈 문자열·공백뿐인 name은 검색어 없음으로 접혀 전체 조회 쿼리를 탄다")
    void getPlayers_blankName_fallsBackToUnfilteredQuery(String blankName) {
        // given: ?name= 처럼 값 없이 붙은 파라미터는 Spring이 null이 아니라 빈 문자열로 넘긴다
        given(playerRepository.findAllByOrderByNameAsc()).willReturn(List.of(playerOf(1L, "강백호")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, blankName);

        // then
        assertThat(result).containsExactly(new PlayerResponse(1L, "강백호"));
        verify(playerRepository).findAllByOrderByNameAsc();
        verifyNoMoreInteractions(playerRepository); // LIKE '%%' 쿼리를 헛돌리지 않는다
    }

    @Test
    @DisplayName("[USER-PL-15] teamId가 있고 name이 공백뿐이면 구단 필터만 건 쿼리를 탄다")
    void getPlayers_blankNameWithTeamId_usesTeamFilterOnly() {
        // given
        given(playerRepository.findAllByTeam_IdOrderByNameAsc(6L))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(6L, "  ");

        // then
        assertThat(result).containsExactly(new PlayerResponse(2L, "김도영"));
        verify(playerRepository).findAllByTeam_IdOrderByNameAsc(6L);
        verifyNoMoreInteractions(playerRepository);
    }

    @Test
    @DisplayName("[USER-PL-15] name 앞뒤 공백은 제거한 뒤 검색어로 넘긴다")
    void getPlayers_nameWithSurroundingWhitespace_isTrimmed() {
        // given: 트림하지 않으면 LIKE '% 도영 %'가 되어 정상 이름이 하나도 걸리지 않는다
        given(playerRepository.findAllByNameContainingOrderByNameAsc("도영"))
                .willReturn(List.of(playerOf(2L, "김도영")));

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, "  도영  ");

        // then
        assertThat(result).containsExactly(new PlayerResponse(2L, "김도영"));
        verify(playerRepository).findAllByNameContainingOrderByNameAsc("도영");
    }

    @Test
    @DisplayName("[USER-PL-16] 어떤 선수 이름과도 일치하지 않는 name이면 예외 없이 빈 리스트를 반환한다")
    void getPlayers_noNameMatch_returnsEmptyListWithoutException() {
        // given
        given(playerRepository.findAllByNameContainingOrderByNameAsc("없는이름"))
                .willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = playerService.getPlayers(null, "없는이름");

        // then
        assertThat(result).isEmpty();
    }
}
