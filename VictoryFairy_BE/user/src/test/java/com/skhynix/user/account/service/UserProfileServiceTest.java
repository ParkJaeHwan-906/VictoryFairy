package com.skhynix.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.support.service.SupportService;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link UserProfileService#getMyProfile(Long)}을 협력 객체 전부 목으로 대체해 단위로 검증한다.
 * 요구사항: {@code docs/requirements/user/me-profile.md}(USER-ME-13 ~ 22). DB·스프링 컨텍스트 없음
 * (USER-ME-1~6·26~29는 DB 라운드트립이 필요해 이 테스트로 증명할 수 없다).
 *
 * <p>엔티티는 {@code id}에 setter가 없어 {@link ReflectionTestUtils#setField}로 채운다
 * ({@code SupportServiceTest}와 같은 패턴).
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private UserBqRepository userBqRepository;

    @Mock
    private SupportService supportService;

    @InjectMocks
    private UserProfileService userProfileService;

    private static UserAccount accountWithPoint(String nickname, long point) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("encoded").build();
        ReflectionTestUtils.setField(account, "point", point);
        return account;
    }

    private static Team teamOf(Long id, String name) {
        Team team = Team.builder().name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static UserSupportTeam activeSupportTeamOf(UserAccount account, Team team) {
        return UserSupportTeam.builder().userAccount(account).team(team).build();
    }

    private static UserBq bqOf(UserAccount account, long bqScore) {
        UserBq bq = UserBq.builder().userAccount(account).build();
        ReflectionTestUtils.setField(bq, "bqScore", bqScore);
        return bq;
    }

    private static PlayerResponse playerOf(Long playerId, String playerName, String number, String position) {
        return new PlayerResponse(6L, "KIA", playerId, playerName, number, position);
    }

    @Test
    @DisplayName("[USER-ME-14, 15, 17, 18] 응원 구단·bq 행이 모두 있으면 5개 필드(닉네임·응원구단·응원선수·포인트·"
            + "누적점수)가 각 출처 값 그대로 조립된다")
    void getMyProfile_allSourcesPresent_assemblesAllFiveFields() {
        // given
        UserAccount account = accountWithPoint("nick", 1200L);
        Team kia = teamOf(6L, "KIA");
        UserSupportTeam supportTeam = activeSupportTeamOf(account, kia);
        UserBq bq = bqOf(account, 340L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeam));
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.of(bq));
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.nickname()).isEqualTo("nick");
        assertThat(response.supportTeam()).isEqualTo(new TeamResponse(6L, "KIA"));
        assertThat(response.point()).isEqualTo(1200L);
        assertThat(response.bqScore()).isEqualTo(340L);
    }

    @Test
    @DisplayName("[USER-ME-32] 응원 선수가 2명이면 응답의 supportPlayers에 그 목록이 그대로 실린다")
    void getMyProfile_twoSupportedPlayers_returnsThemAsIs() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        List<PlayerResponse> players = List.of(
                playerOf(10L, "김선수", "1", "INFIELDER"),
                playerOf(11L, "이선수", null, null));
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(players);

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.supportPlayers()).containsExactlyElementsOf(players);
    }

    @Test
    @DisplayName("[USER-ME-34] currentSupportedPlayers가 빈 리스트를 반환하면 supportPlayers도 빈 리스트다")
    void getMyProfile_supportServiceReturnsEmptyList_supportPlayersIsEmpty() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.supportPlayers()).isEmpty();
    }

    @Test
    @DisplayName("[USER-ME-35] 응원 구단이 없어도(supportTeam null) 응원 선수 조회는 정상 수행되고 200 경로가 유지된다")
    void getMyProfile_noSupportTeam_stillLooksUpSupportedPlayersAndSucceeds() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        List<PlayerResponse> players = List.of(playerOf(10L, "김선수", "1", "INFIELDER"));
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(players);

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.supportTeam()).isNull();
        assertThat(response.supportPlayers()).containsExactlyElementsOf(players);
        verify(supportService).currentSupportedPlayers(ACCOUNT_ID);
    }

    @Test
    @DisplayName("[USER-ME-16, 안전망] 현재 응원 중인 구단 행이 없으면 예외 없이 supportTeam이 null인 프로필을 반환한다")
    void getMyProfile_noActiveSupportTeamRow_returnsNullSupportTeamWithoutException() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.of(bqOf(account, 0L)));
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.supportTeam()).isNull();
    }

    @Test
    @DisplayName("[USER-ME-19, 안전망] users_bq 행이 없으면 bqScore가 0인 프로필을 반환한다(null·예외 아님)")
    void getMyProfile_noUsersBqRow_returnsZeroBqScore() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.bqScore()).isZero();
    }

    @Test
    @DisplayName("[USER-ME-19, 20] users_bq 행이 없어 안전망이 작동한 뒤에도 users_bq에 새 행을 만들지 않는다")
    void getMyProfile_bqSafetyNetTriggered_doesNotCreateUsersBqRow() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        verify(userBqRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-ME-20] 조회는 계정·응원구단·bq 리포지토리 어디에도 save를 호출하지 않는다(정상 경로)")
    void getMyProfile_normalPath_neverCallsAnySaveMethod() {
        // given
        UserAccount account = accountWithPoint("nick", 1200L);
        Team kia = teamOf(6L, "KIA");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(activeSupportTeamOf(account, kia)));
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.of(bqOf(account, 340L)));
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        verify(userAccountRepository, never()).save(any());
        verify(userSupportTeamRepository, never()).save(any());
        verify(userBqRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-ME-15, 22] 응원 구단 조회는 oppose is null 조건에 구단을 함께 가져오는 "
            + "findWithTeamByUserAccount_IdAndOpposeIsNull만 쓰고, 구단을 안 가져오는 동일 조건 메서드도 "
            + "재응원용 findByUserAccount_IdAndTeam_Id도 호출하지 않는다")
    void getMyProfile_looksUpSupportTeamUsingOpposeIsNullMethodOnly() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        verify(userSupportTeamRepository).findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID);
        // 구단명을 응답에 싣는 경로라 구단을 함께 가져오는 쪽을 써야 한다. 아래 메서드로 되돌리면
        // getTeam() 이 LAZY 프록시를 초기화해 SELECT 가 1회 늘고 USER-ME-22(≤4회)가 깨진다.
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndOpposeIsNull(any());
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndTeam_Id(any(), any());
    }

    @Test
    @DisplayName("[요구사항 미기재, 경계] 인증 근거가 된 계정 id가 조회 시점에 더 이상 존재하지 않으면 "
            + "UNAUTHENTICATED 예외를 던진다(필터가 못 찾았을 때와 같은 401로 맞춘 것)")
    void getMyProfile_accountNotFound_throwsUnauthenticated() {
        // given
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userProfileService.getMyProfile(ACCOUNT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        verify(userSupportTeamRepository, never()).findWithTeamByUserAccount_IdAndOpposeIsNull(any());
        verify(userBqRepository, never()).findByUserAccount_Id(any());
        verify(supportService, never()).currentSupportedPlayers(any());
    }
}
