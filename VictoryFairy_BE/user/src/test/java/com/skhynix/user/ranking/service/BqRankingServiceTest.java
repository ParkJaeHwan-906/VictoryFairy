package com.skhynix.user.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.BqRankingEntryView;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.ranking.dto.BqRankingResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link BqRankingService}를 협력 객체(리포지토리) 전부 목으로 대체해 단위로 검증한다. 요구사항:
 * {@code docs/requirements/user/team-bq-ranking.md}(USER-RK-1~84).
 *
 * <p>DB·스프링 컨텍스트 없음 — 리포지토리 JPQL 자체가 지키는 조항(모집단 조인 조건·coalesce·탈퇴 계정
 * 포함 등, USER-RK-10~12·14·16·18)은 이 테스트로 증명할 수 없다({@code UserBqRepository}의 실기동
 * 검증 대상). 여기서는 {@link BqRankingService}가 리포지토리 결과를 받아 순위 숫자를 매기는 로직
 * (동점 공동 순위·상한 전달·안전망 분기)만 본다.
 */
@ExtendWith(MockitoExtension.class)
class BqRankingServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long TEAM_ID = 6L;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private UserBqRepository userBqRepository;

    @InjectMocks
    private BqRankingService bqRankingService;

    private static Team teamOf(Long id) {
        Team team = Team.builder().name("KIA").build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static UserSupportTeam activeSupportTeamOf(Long teamId) {
        UserAccount account = UserAccount.builder().nickname("nick").password("encoded").build();
        return UserSupportTeam.builder().userAccount(account).team(teamOf(teamId)).build();
    }

    private static BqRankingEntryView entryOf(String nickname, String profileImgUrl, long bqScore) {
        return new BqRankingEntryView() {
            @Override
            public String getNickname() {
                return nickname;
            }

            @Override
            public String getProfileImgUrl() {
                return profileImgUrl;
            }

            @Override
            public long getBqScore() {
                return bqScore;
            }
        };
    }

    private void stubActiveTeam(Long teamId) {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(activeSupportTeamOf(teamId)));
    }

    // ---------- 동점 공동 순위 (USER-RK-17, 18) ----------

    @Test
    @DisplayName("[USER-RK-17, 18] 점수 50·50·30(id 오름차순 배치) 세 계정은 순위가 1·1·3으로 매겨진다"
            + "(순차 1·2·3도 밀집 1·1·2도 아니다)")
    void getRanking_tiedScores_assignsCompetitionRanking() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of(
                entryOf("a", null, 50L),
                entryOf("b", null, 50L),
                entryOf("c", null, 30L)));

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result).extracting(BqRankingResponse::rank).containsExactly(1, 1, 3);
    }

    @Test
    @DisplayName("[USER-RK-16] 점수 50·30·10 세 계정은 순위가 1·2·3으로 매겨진다(동점 없음)")
    void getRanking_distinctScores_assignsSequentialRank() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of(
                entryOf("a", null, 50L),
                entryOf("b", null, 30L),
                entryOf("c", null, 10L)));

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result).extracting(BqRankingResponse::rank).containsExactly(1, 2, 3);
    }

    // ---------- 3건·10건 상한 (USER-RK-31, 41) ----------

    @Test
    @DisplayName("[USER-RK-31] topRanking은 리포지토리에 상한 3을 그대로 전달한다"
            + "(실제 절단·동점 배치 순서는 findTeamRanking의 ORDER BY+LIMIT이 하는 일이라 이 유닛 "
            + "테스트로는 '3을 요청했다'까지만 증명한다 — 리포지토리 쿼리 계약, 실기동 검증 대상)")
    void getTopRanking_passesLimitOfThreeToRepository() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of());

        // when
        bqRankingService.getTopRanking(ACCOUNT_ID);

        // then
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        verify(userBqRepository).findTeamRanking(eq(TEAM_ID), limitCaptor.capture());
        assertThat(limitCaptor.getValue().max()).isEqualTo(3);
    }

    @Test
    @DisplayName("[USER-RK-41] ranking은 리포지토리에 상한 10을 그대로 전달한다"
            + "(같은 이유로 절단 자체는 리포지토리 쿼리 계약, 실기동 검증 대상)")
    void getRanking_passesLimitOfTenToRepository() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of());

        // when
        bqRankingService.getRanking(ACCOUNT_ID);

        // then
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        verify(userBqRepository).findTeamRanking(eq(TEAM_ID), limitCaptor.capture());
        assertThat(limitCaptor.getValue().max()).isEqualTo(10);
    }

    @Test
    @DisplayName("[USER-RK-33, 42] 모집단이 상한보다 적으면(3위 자리 중 2건만) 있는 만큼만 담긴다"
            + "(null로 채우거나 예외를 던지지 않는다)")
    void getRanking_populationSmallerThanLimit_returnsOnlyAvailableEntries() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of(
                entryOf("a", null, 50L),
                entryOf("b", null, 30L)));

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(BqRankingResponse::rank).containsExactly(1, 2);
    }

    // ---------- 구단 없음 (USER-RK-60, 61) ----------

    @Test
    @DisplayName("[USER-RK-60] 활성 응원 구단이 없으면 topRanking은 빈 목록을 반환하고 순위 조회는 나가지 "
            + "않는다")
    void getTopRanking_noActiveTeam_returnsEmptyListWithoutQuerying() {
        // given
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());

        // when
        List<BqRankingResponse> result = bqRankingService.getTopRanking(ACCOUNT_ID);

        // then
        assertThat(result).isEmpty();
        verify(userBqRepository, never()).findTeamRanking(any(), any());
    }

    @Test
    @DisplayName("[USER-RK-60] 활성 응원 구단이 없으면 ranking도 빈 목록을 반환한다")
    void getRanking_noActiveTeam_returnsEmptyList() {
        // given
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-RK-61] 활성 응원 구단이 없으면 myRanking은 null을 반환하고 순위 조회는 나가지 "
            + "않는다(빈 객체·예외가 아니다)")
    void getMyRanking_noActiveTeam_returnsNullWithoutQuerying() {
        // given
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());

        // when
        BqRankingResponse result = bqRankingService.getMyRanking(ACCOUNT_ID);

        // then
        assertThat(result).isNull();
        verify(userBqRepository, never()).findRankingEntry(any());
    }

    // ---------- users_bq 행 없음(0점 포함, USER-RK-14) ----------

    @Test
    @DisplayName("[USER-RK-13, 14] users_bq 행이 없는 계정(0점으로 흡수된 항목)도 모집단 목록에 그대로 "
            + "담긴다 — 빠지지도 500도 아니다")
    void getRanking_entryWithZeroScore_isIncludedAsIs() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of(
                entryOf("a", null, 30L),
                entryOf("b", null, 0L),
                entryOf("c", null, 0L)));

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result).hasSize(3);
        assertThat(result).extracting(BqRankingResponse::bqScore).containsExactly(30L, 0L, 0L);
        assertThat(result).extracting(BqRankingResponse::rank).containsExactly(1, 2, 2);
    }

    @Test
    @DisplayName("[USER-RK-24] profileImgUrl이 NULL인 계정 항목은 응답에서도 profileImgUrl이 null이다"
            + "(빈 문자열·키 생략이 아니다)")
    void getRanking_entryWithNullProfileImgUrl_mapsToNullProfileImgUrl() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class)))
                .willReturn(List.of(entryOf("a", null, 30L)));

        // when
        List<BqRankingResponse> result = bqRankingService.getRanking(ACCOUNT_ID);

        // then
        assertThat(result.get(0).profileImgUrl()).isNull();
    }

    // ---------- 본인 순위 = count(상위) + 1 (rankOf) ----------

    @Test
    @DisplayName("[USER-RK-19] rankOf는 그 구단에서 주어진 점수보다 높은 계정 수에 1을 더한 값이다")
    void rankOf_returnsCountOfHigherScoresPlusOne() {
        // given
        given(userBqRepository.countHigherInTeam(TEAM_ID, 340L)).willReturn(6L);

        // when
        int rank = bqRankingService.rankOf(TEAM_ID, 340L);

        // then
        assertThat(rank).isEqualTo(7);
    }

    @Test
    @DisplayName("[USER-RK-53] 상위 점수가 0명이면 본인 순위는 1이다(본인 포함 여부는 리포지토리 계약, 실기동 검증)")
    void rankOf_noOneHigher_returnsRankOne() {
        // given
        given(userBqRepository.countHigherInTeam(TEAM_ID, 0L)).willReturn(0L);

        // when
        int rank = bqRankingService.rankOf(TEAM_ID, 0L);

        // then
        assertThat(rank).isEqualTo(1);
    }

    @Test
    @DisplayName("[USER-RK-50, 51] myRanking은 findRankingEntry로 얻은 값과 rankOf가 계산한 순위를 그대로 "
            + "담는다")
    void getMyRanking_activeTeam_returnsEntryWithComputedRank() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findRankingEntry(ACCOUNT_ID))
                .willReturn(Optional.of(entryOf("gildong", "user-profile-img/a.jpg", 120L)));
        given(userBqRepository.countHigherInTeam(TEAM_ID, 120L)).willReturn(6L);

        // when
        BqRankingResponse result = bqRankingService.getMyRanking(ACCOUNT_ID);

        // then
        assertThat(result.rank()).isEqualTo(7);
        assertThat(result.nickname()).isEqualTo("gildong");
        assertThat(result.profileImgUrl()).isEqualTo("user-profile-img/a.jpg");
        assertThat(result.bqScore()).isEqualTo(120L);
    }

    @Test
    @DisplayName("[USER-RK-53] 모집단이 커서 순위가 11 이상이어도 rank는 상한 없이 그대로 반환된다"
            + "(10위 밖이라고 null·상한값으로 뭉개지 않는다)")
    void getMyRanking_rankBeyondTen_returnsRawRankWithoutCapping() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findRankingEntry(ACCOUNT_ID))
                .willReturn(Optional.of(entryOf("nick", null, 5L)));
        given(userBqRepository.countHigherInTeam(TEAM_ID, 5L)).willReturn(186L);

        // when
        BqRankingResponse result = bqRankingService.getMyRanking(ACCOUNT_ID);

        // then
        assertThat(result.rank()).isEqualTo(187);
    }

    @Test
    @DisplayName("[USER-RK-54] users_bq 행이 없는 요청자(0점으로 흡수된 항목)도 myRanking이 200 값을 "
            + "반환한다 — 예외가 아니다")
    void getMyRanking_noUsersBqRow_stillReturnsZeroScoreEntry() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findRankingEntry(ACCOUNT_ID))
                .willReturn(Optional.of(entryOf("nick", null, 0L)));
        given(userBqRepository.countHigherInTeam(TEAM_ID, 0L)).willReturn(3L);

        // when
        BqRankingResponse result = bqRankingService.getMyRanking(ACCOUNT_ID);

        // then
        assertThat(result.bqScore()).isEqualTo(0L);
        assertThat(result.rank()).isEqualTo(4);
    }

    @Test
    @DisplayName("[요구사항 미기재, 경계] 활성 응원 구단은 있는데 findRankingEntry가 비어 있으면(계정이 "
            + "그 사이 사라진 레이스) UNAUTHENTICATED 예외를 던진다")
    void getMyRanking_activeTeamButEntryMissing_throwsUnauthenticated() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findRankingEntry(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> bqRankingService.getMyRanking(ACCOUNT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // ---------- 조회가 쓰기를 하지 않음 (USER-RK-80) ----------

    @Test
    @DisplayName("[USER-RK-80] 세 조회 경로 모두 어떤 리포지토리에도 save를 호출하지 않는다")
    void allReadPaths_neverCallSave() {
        // given
        stubActiveTeam(TEAM_ID);
        given(userBqRepository.findTeamRanking(eq(TEAM_ID), any(Limit.class))).willReturn(List.of());
        given(userBqRepository.findRankingEntry(ACCOUNT_ID))
                .willReturn(Optional.of(entryOf("nick", null, 0L)));
        given(userBqRepository.countHigherInTeam(eq(TEAM_ID), anyLong())).willReturn(0L);

        // when
        bqRankingService.getTopRanking(ACCOUNT_ID);
        bqRankingService.getRanking(ACCOUNT_ID);
        bqRankingService.getMyRanking(ACCOUNT_ID);

        // then
        verify(userBqRepository, never()).save(any());
        verify(userSupportTeamRepository, never()).save(any());
    }
}
