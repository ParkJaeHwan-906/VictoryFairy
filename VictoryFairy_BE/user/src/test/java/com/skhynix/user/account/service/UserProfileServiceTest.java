package com.skhynix.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.character.entity.Character;
import com.skhynix.domain.character.entity.CharacterItem;
import com.skhynix.domain.character.entity.ItemType;
import com.skhynix.domain.character.entity.UserCharacterInventory;
import com.skhynix.domain.character.entity.UserCharacterItemInventory;
import com.skhynix.domain.character.repository.UserCharacterInventoryRepository;
import com.skhynix.domain.character.repository.UserCharacterItemInventoryRepository;
import com.skhynix.domain.quiz.repository.QuizSubmitAccuracyView;
import com.skhynix.domain.quiz.repository.QuizUserSubmitRepository;
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
import java.math.BigDecimal;
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

    @Mock
    private UserCharacterInventoryRepository characterInventoryRepository;

    @Mock
    private UserCharacterItemInventoryRepository characterItemInventoryRepository;

    @Mock
    private QuizUserSubmitRepository quizUserSubmitRepository;

    @InjectMocks
    private UserProfileService userProfileService;

    private static UserAccount accountWithPoint(String nickname, long point) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("encoded").build();
        ReflectionTestUtils.setField(account, "point", point);
        return account;
    }

    // QuizSubmitAccuracyView 는 인터페이스 프로젝션이라 익명 구현으로 값을 만든다.
    // correctCount 를 Long 으로 받는 것은 오타가 아니다 — totalCount == 0 인 계정에서 SUM 은 NULL 이라
    // 그 분기를 검증하려면 null 을 넣을 수 있어야 한다(USER-ME-40, 프로덕션 javadoc 참고).
    private static QuizSubmitAccuracyView accuracyView(long totalCount, Long correctCount) {
        return new QuizSubmitAccuracyView() {
            @Override
            public long getTotalCount() {
                return totalCount;
            }

            @Override
            public Long getCorrectCount() {
                return correctCount;
            }
        };
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
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

    @Test
    @DisplayName("[USER-SP-44] GET /me 경로는 계정 행 비관적 락(findWithLockById)을 절대 타지 않는다"
            + " — SupportService.lockAccount는 쓰기 경로 전용이며 /me는 SupportService를 조회 전용 메서드"
            + "(currentSupportedPlayers)로만 호출한다")
    void getMyProfile_neverLocksAccount() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());

        // when
        userProfileService.getMyProfile(ACCOUNT_ID);

        // then: UserProfileService가 직접 들고 있는 UserAccountRepository 목에 대한 검증이다.
        // SupportService는 이 클래스에서 통째로 목이라 그 내부의 lockAccount 호출 여부는 이 테스트로
        // 직접 볼 수 없다 — 그 부분은 SupportServiceTest.currentSupportedPlayers_neverLocksAccount가
        // 대신 고정한다(같은 리포지토리 인스턴스가 아니라 목 조합이 다르기 때문).
        verify(userAccountRepository, never()).findWithLockById(any());
    }

    // ---------- 퀴즈 정답률 (USER-ME-37 ~ 44) ----------
    // aggregateAccuracy 자체의 HQL 실행·SELECT 1회 고정(USER-ME-44)은 이 목 기반 유닛 테스트로는
    // 증명할 수 없다(DB 라운드트립 필요) — 나눗셈·반올림·안전망 로직만 검증한다.

    private void stubQuizAccuracyBasics(UserAccount account, QuizSubmitAccuracyView view) {
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(view);
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());
    }

    @Test
    @DisplayName("[USER-ME-37, 40] quiz_users_submit 행이 한 건도 없으면(totalCount=0, correctCount=NULL) "
            + "예외 없이 quizAccuracy가 0인 프로필을 반환한다 — SUM의 NULL을 읽지 않는 분기다")
    void getMyProfile_noQuizSubmissions_quizAccuracyIsZero() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(0, null));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("[USER-ME-38] 40건 중 26건이 정답이면 quizAccuracy는 0.65다(correctCount/totalCount)")
    void getMyProfile_quizAccuracy_computesCorrectOverTotal() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(40, 26L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy()).isEqualByComparingTo(new BigDecimal("0.65"));
    }

    @Test
    @DisplayName("[USER-ME-39] 미답 행이 분모에 포함된 결과(10건 중 6건만 정답)를 그대로 담아 0.6을 반환한다 "
            + "— 미답 행이 분모에서 빠지지 않는다는 사실 자체는 aggregateAccuracy의 SQL이 보장하며(DB 실행 "
            + "검증 필요, 아래 '미커버 영역' 참고) 여기서는 그 결과값을 서비스가 그대로 나눗셈하는지만 본다")
    void getMyProfile_quizAccuracy_unansweredRowsCountTowardDenominator() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(10, 6L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy()).isEqualByComparingTo(new BigDecimal("0.6"));
    }

    @Test
    @DisplayName("[USER-ME-41] 1/16(=0.0625)은 HALF_UP으로 0.063이 된다 — 0.062가 아니다(반올림 회귀 기준점)")
    void getMyProfile_quizAccuracy_roundsHalfUpAtThirdDecimal() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(16, 1L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy().toPlainString()).isEqualTo("0.063");
    }

    @Test
    @DisplayName("[USER-ME-41] 전건 정답이면 quizAccuracy는 1이다(1.000이 아니다 — 후행 0 미보존)")
    void getMyProfile_quizAccuracy_allCorrect_returnsOneWithoutTrailingZeros() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(5, 5L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy().toPlainString()).isEqualTo("1");
    }

    @Test
    @DisplayName("[USER-ME-41] 전건 오답이면 quizAccuracy는 0이다")
    void getMyProfile_quizAccuracy_allWrong_returnsZero() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(5, 0L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy().toPlainString()).isEqualTo("0");
    }

    @Test
    @DisplayName("[USER-ME-41] 1/2(=0.5)는 0.500이 아니라 0.5로 담긴다 — 자릿수 패딩은 서버가 하지 않는다")
    void getMyProfile_quizAccuracy_doesNotPadTrailingZeros() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(2, 1L));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.quizAccuracy().toPlainString()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("[USER-ME-43] 정답률 집계는 계정 id 하나만으로 딱 1회 호출된다 — 경기·이닝·날짜로 좁히는 "
            + "별도 파라미터가 없다(전 기간 누적)")
    void getMyProfile_quizAccuracy_aggregatesWithAccountIdOnlyOnce() {
        // given
        UserAccount account = accountWithPoint("nick", 0L);
        stubQuizAccuracyBasics(account, accuracyView(3, 2L));

        // when
        userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        verify(quizUserSubmitRepository, org.mockito.Mockito.times(1)).aggregateAccuracy(ACCOUNT_ID);
    }

    // ---------- 캐릭터·착용 아이템 ----------

    private static Character characterOf(Long id, String img) {
        Character character = Character.builder().name("승리요정").img(img).build();
        ReflectionTestUtils.setField(character, "id", id);
        return character;
    }

    private static UserCharacterItemInventory wornItemOf(Long itemTypeId, String itemTypeName,
            String usingImg) {
        ItemType itemType = ItemType.builder().name(itemTypeName).build();
        ReflectionTestUtils.setField(itemType, "id", itemTypeId);
        CharacterItem item = CharacterItem.builder()
                .character(characterOf(1L, "characters/victory-fairy.svg"))
                .itemType(itemType)
                .name(itemTypeName + " 아이템")
                .displayImg("stores/x.svg")
                .usingImg(usingImg)
                .price(100L)
                .build();
        return UserCharacterItemInventory.builder()
                .userAccount(accountWithPoint("nick", 0L))
                .characterItem(item)
                .active(true)
                .build();
    }

    private void stubProfileBasics(UserAccount account) {
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userBqRepository.findByUserAccount_Id(ACCOUNT_ID)).willReturn(Optional.empty());
        given(quizUserSubmitRepository.aggregateAccuracy(ACCOUNT_ID)).willReturn(accuracyView(0, 0L));
        given(supportService.currentSupportedPlayers(ACCOUNT_ID)).willReturn(List.of());
    }

    @Test
    @DisplayName("사용 중인 캐릭터의 이미지 EP 와 착용 중인 아이템의 착용용 이미지 EP 를 함께 싣는다")
    void getMyProfile_withCharacter_includesCharacterAndWornItemEndpoints() {
        // given
        stubProfileBasics(accountWithPoint("nick", 0L));
        UserCharacterInventory owned = UserCharacterInventory.builder()
                .userAccount(accountWithPoint("nick", 0L))
                .character(characterOf(1L, "characters/victory-fairy.svg"))
                .active(true)
                .build();
        given(characterInventoryRepository
                .findWithCharacterByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(Optional.of(owned));
        given(characterItemInventoryRepository.findAllByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(List.of(wornItemOf(1L, "의상", "items/cloth/basic.svg")));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.characterImgUrl()).isEqualTo("characters/victory-fairy.svg");
        assertThat(response.characterItems()).hasSize(1);
        assertThat(response.characterItems().get(0).itemType()).isEqualTo("의상");
        assertThat(response.characterItems().get(0).imgUrl()).isEqualTo("items/cloth/basic.svg");
    }

    @Test
    @DisplayName("착용 아이템은 부위 id 순으로 정렬돼 겹치는 순서가 요청마다 흔들리지 않는다")
    void getMyProfile_wornItems_sortedByItemTypeId() {
        // given
        stubProfileBasics(accountWithPoint("nick", 0L));
        given(characterInventoryRepository
                .findWithCharacterByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(Optional.empty());
        // 리포지토리가 부위 순서를 보장하지 않는다는 전제로 일부러 뒤집어 준다.
        given(characterItemInventoryRepository.findAllByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(List.of(
                        wornItemOf(3L, "소품", "items/item/ball.svg"),
                        wornItemOf(1L, "의상", "items/cloth/basic.svg"),
                        wornItemOf(2L, "모자", "items/head/cap-blue.svg")));

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.characterItems())
                .extracting(item -> item.itemType())
                .containsExactly("의상", "모자", "소품");
    }

    @Test
    @DisplayName("캐릭터를 못 받은 계정도 200 을 유지한다 — characterImgUrl 은 null, characterItems 는 빈 배열")
    void getMyProfile_withoutCharacter_returnsNullAndEmptyList() {
        // given
        stubProfileBasics(accountWithPoint("nick", 0L));
        given(characterInventoryRepository
                .findWithCharacterByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(characterItemInventoryRepository.findAllByUserAccount_IdAndActiveIsTrue(ACCOUNT_ID))
                .willReturn(List.of());

        // when
        UserAccountResponse response = userProfileService.getMyProfile(ACCOUNT_ID);

        // then
        assertThat(response.characterImgUrl()).isNull();
        assertThat(response.characterItems()).isEmpty();
    }
}
