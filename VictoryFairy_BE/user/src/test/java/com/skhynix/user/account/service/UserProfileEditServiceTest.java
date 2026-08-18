package com.skhynix.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.dto.NicknameChangeCooldownResponse;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.NicknameChangeCooldownPolicy;
import com.skhynix.user.auth.service.AuthService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link UserProfileEditService}를 협력 객체(리포지토리·인코더·AuthService·Clock)를 목으로 대체해
 * 단위로 검증한다. DB·스프링 컨텍스트 없음. 요구사항: {@code docs/requirements/user/profile-edit.md}.
 *
 * <p>{@code Clock}은 {@link #FIXED_CLOCK}으로 고정해 {@code AuthService.issueTokens}에 넘기는 시각을
 * 결정적으로 검증한다(모듈 컨벤션 "새 협력자·새 빈을 추가할 때 흔한 함정" 참고 — {@code @InjectMocks}에
 * 새 협력자 mock을 빠뜨리면 컴파일은 통과하고 런타임 NPE로만 깨진다).
 *
 * <p>{@code nicknameChangedAt}은 KST 벽시계 값이고 쓰는 쪽·읽는 쪽이 전부 {@code Clock} 빈을 거쳐야
 * 일관된다({@code LocalDateTime.now()}가 한 줄이라도 섞이면 UTC 파드에서 9시간 어긋난다) — 이 클래스의
 * "UTC/KST 날짜 경계" 회귀 테스트 2건이 그 계약을 증명한다({@code GameServiceTest}의 같은 패턴 참고).
 */
@ExtendWith(MockitoExtension.class)
class UserProfileEditServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-17T03:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Seoul"));
    // FIXED_CLOCK이 나타내는 KST 벽시계 값(2026-08-17T12:00:00) — 서비스가 Clock에서 읽는 "지금"과
    // 문자 그대로 같은 값이어야 하므로 별도로 계산하지 않고 리터럴로 고정한다.
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 17, 12, 0, 0);

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthService authService;

    @InjectMocks
    private UserProfileEditService userProfileEditService;

    private UserProfileEditService serviceWithFixedClock() {
        return new UserProfileEditService(userAccountRepository, passwordEncoder, authService, FIXED_CLOCK);
    }

    /**
     * 마지막 닉네임 변경 시각까지 채운 계정을 만든다. 엔티티에 전용 setter가 없어(컨벤션상 두지 않음)
     * 엔티티 자신의 {@code changeNickname}을 같은 닉네임 값으로 다시 호출해 변경 시각만 채운다 — 닉네임
     * 값은 그대로 유지되므로 fixture 구성 목적에 어긋나지 않는다.
     */
    private UserAccount accountWith(String nickname, String encodedPassword, LocalDateTime lastChangedAt) {
        UserAccount account = accountWith(nickname, encodedPassword);
        account.changeNickname(nickname, lastChangedAt);
        return account;
    }

    private UserAccount accountWith(String nickname, String encodedPassword) {
        return UserAccount.builder()
                .user(null)
                .nickname(nickname)
                .password(encodedPassword)
                .build();
    }

    // ---------- 닉네임 변경 ----------

    @Test
    @DisplayName("[USER-PE-8] 정책을 만족하고 다른 계정이 점유하지 않은 새 닉네임이면 엔티티의 닉네임이 교체된다"
            + "(계정은 컬럼 도입 이전 상태와 같은 NULL 쿨다운이라 쿨다운 조회는 LocalDateTime.now(clock)을 필요로"
            + " 한다 — Clock을 고정하지 않으면 NPE로 깨진다)")
    void updateNickname_newAndNotDuplicated_replacesNickname() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("길동gil9")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "길동gil9");

        // then
        assertThat(account.getNickname()).isEqualTo("길동gil9");
        verify(authService).isNicknameDuplicated("길동gil9");
    }

    @Test
    @DisplayName("[USER-PE-17, USER-PE-33, USER-PE-42] 요청 닉네임이 현재 닉네임과 완전히 같으면 "
            + "SAME_AS_CURRENT_NICKNAME이 발생하고, 닉네임과 변경 시각 컬럼이 둘 다 불변이며 중복 조회"
            + "(isNicknameDuplicated)는 호출되지 않는다(동일 판정이 중복 조회·쿨다운보다 먼저다)")
    void updateNickname_sameAsCurrentNickname_throwsWithoutCheckingDuplicate() {
        // given: 쿨다운 판정 이전에 잘리는 경로임을 함께 증명하기 위해 이미 기록된 변경 시각을 채워 둔다
        LocalDateTime previouslyChanged = LocalDateTime.of(2026, 7, 1, 0, 0, 0);
        UserAccount account = accountWith("길동", "encoded", previouslyChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updateNickname(ACCOUNT_ID, "길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_AS_CURRENT_NICKNAME);

        assertThat(account.getNickname()).isEqualTo("길동");
        assertThat(account.getNicknameChangedAt()).isEqualTo(previouslyChanged);
        verify(authService, never()).isNicknameDuplicated(anyString());
    }

    @Test
    @DisplayName("[USER-PE-16, USER-PE-7] 다른 계정이 점유한 닉네임이면 DUPLICATE_NICKNAME이 발생하고 닉네임은 불변이다")
    void updateNickname_duplicatedByOtherAccount_throwsAndKeepsNicknameUnchanged() {
        // given
        UserAccount account = accountWith("길동", "encoded");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("이미있음")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updateNickname(ACCOUNT_ID, "이미있음"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        assertThat(account.getNickname()).isEqualTo("길동");
    }

    @Test
    @DisplayName("[USER-PE-19] 닉네임 변경 성공은 AuthService·PasswordEncoder와 상호작용하지 않는다(토큰·비밀번호 미변경)"
            + "(쿨다운 조회가 LocalDateTime.now(clock)을 필요로 해 Clock을 고정하지 않으면 NPE로 깨진다)")
    void updateNickname_success_doesNotTouchTokensOrPassword() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then
        verify(authService, never()).issueTokens(any(), any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("(요구사항 ID 없음, USER-WD 패턴 대응) 존재하지 않는 계정 id로 닉네임 변경을 시도하면 UNAUTHENTICATED가 발생한다")
    void updateNickname_accountNotFound_throwsUnauthenticated() {
        // given
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updateNickname(ACCOUNT_ID, "아무거나"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    // ---------- 닉네임 변경 쿨다운 30일 (USER-PE-40~49) ----------

    @Test
    @DisplayName("[USER-PE-44] 마지막 변경으로부터 정확히 30일이 지났으면 경계는 허용 쪽이라 닉네임이 교체된다")
    void updateNickname_exactlyThirtyDaysElapsed_isAllowed() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime lastChanged = FIXED_NOW.minusDays(NicknameChangeCooldownPolicy.COOLDOWN_DAYS);
        UserAccount account = accountWith("길동", "encoded", lastChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then
        assertThat(account.getNickname()).isEqualTo("철수");
        assertThat(account.getNicknameChangedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("[USER-PE-43, 44] 마지막 변경으로부터 30일-1초(1초 모자람) 지났으면 429 NICKNAME_CHANGE_COOLDOWN이 "
            + "발생하고 닉네임과 변경 시각 컬럼이 둘 다 불변이다")
    void updateNickname_oneSecondShortOfCooldown_throws429AndKeepsNicknameAndColumnUnchanged() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime lastChanged =
                FIXED_NOW.minusDays(NicknameChangeCooldownPolicy.COOLDOWN_DAYS).plusSeconds(1);
        UserAccount account = accountWith("길동", "encoded", lastChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> service.updateNickname(ACCOUNT_ID, "철수"))
                .isInstanceOf(BusinessDataException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NICKNAME_CHANGE_COOLDOWN);

        assertThat(account.getNickname()).isEqualTo("길동");
        assertThat(account.getNicknameChangedAt()).isEqualTo(lastChanged);
    }

    @Test
    @DisplayName("[USER-PE-45] nicknameChangedAt이 NULL인 계정(한 번도 안 바꿨거나 컬럼 도입 이전)은 "
            + "쿨다운 없이 변경이 성공한다")
    void updateNickname_nullLastChanged_isNotSubjectToCooldown() {
        // given: accountWith(nickname, password)는 builder()만 거쳐 변경 시각이 NULL인 채로 남는다
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded");
        assertThat(account.getNicknameChangedAt()).isNull();
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then
        assertThat(account.getNickname()).isEqualTo("철수");
        assertThat(account.getNicknameChangedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("[USER-PE-41] 이전에 기록된 변경 시각이 있어도 성공하면 새 시각으로 덮어쓴다")
    void updateNickname_success_overwritesPreviousRecordedChangedAt() {
        // given: 쿨다운이 이미 풀린 과거 변경 이력(31일 전)이 있는 계정
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime previouslyChanged =
                FIXED_NOW.minusDays(NicknameChangeCooldownPolicy.COOLDOWN_DAYS).minusDays(1);
        UserAccount account = accountWith("길동", "encoded", previouslyChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then
        assertThat(account.getNicknameChangedAt())
                .isNotEqualTo(previouslyChanged)
                .isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("[USER-PE-42] 실패(DUPLICATE_NICKNAME)하면 이미 기록돼 있던 nicknameChangedAt이 갱신되지 않는다")
    void updateNickname_duplicateFailure_doesNotUpdateRecordedChangedAt() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime previouslyChanged = FIXED_NOW.minusDays(NicknameChangeCooldownPolicy.COOLDOWN_DAYS);
        UserAccount account = accountWith("길동", "encoded", previouslyChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("이미있음")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.updateNickname(ACCOUNT_ID, "이미있음"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        assertThat(account.getNicknameChangedAt()).isEqualTo(previouslyChanged);
    }

    @Test
    @DisplayName("[USER-PE-33 파생 계약] 쿨다운 중인 계정이 이미 점유된 닉네임을 요청하면 429가 아니라 409 "
            + "DUPLICATE_NICKNAME이다(④타 계정 점유 판정이 ⑤쿨다운보다 먼저다)")
    void updateNickname_coolingDownAndDuplicated_prefersDuplicateOver429() {
        // given: 쿨다운 중(1초 전에 바꿈)이면서 요청 닉네임은 타 계정이 점유한 상태
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime lastChanged = FIXED_NOW.minusSeconds(1);
        UserAccount account = accountWith("길동", "encoded", lastChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("이미있음")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.updateNickname(ACCOUNT_ID, "이미있음"))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(BusinessDataException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        assertThat(account.getNickname()).isEqualTo("길동");
        assertThat(account.getNicknameChangedAt()).isEqualTo(lastChanged);
    }

    @Test
    @DisplayName("[USER-PE-43, 47] 쿨다운 중이면 BusinessDataException의 data가 정확히 NicknameChangeCooldownResponse "
            + "하나이고, 그 값이 (마지막 변경 시각 + 30일)을 Asia/Seoul 오프셋 ISO-8601로 렌더링한 문자열과 일치한다 "
            + "(초가 0이어도 \":00\"이 생략되지 않는다 — 다음 변경 가능 시각을 FIXED_CLOCK의 정각(초=0)에서 정확히 "
            + "60초 뒤로 맞춰, 그 결과가 다시 초=0인 시각이 되도록 고른 경계 케이스다)")
    void updateNickname_coolingDown_dataIsExactCooldownResponseWithNonTruncatedSeconds() {
        // given: FIXED_NOW(2026-08-17T12:00:00, 초=0)에서 60초 뒤가 다음 변경 가능 시각이 되도록
        // lastChanged를 역산한다 — 60초 뒤도 정각(초=0)이라 ":00" 생략 함정을 그대로 재현한다.
        UserProfileEditService service = serviceWithFixedClock();
        LocalDateTime lastChanged =
                FIXED_NOW.minusDays(NicknameChangeCooldownPolicy.COOLDOWN_DAYS).plusSeconds(60);
        UserAccount account = accountWith("길동", "encoded", lastChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        NicknameChangeCooldownResponse expected = NicknameChangeCooldownResponse.of(
                NicknameChangeCooldownPolicy.nextChangeableAt(lastChanged), FIXED_CLOCK.getZone());
        // 사전 조건: 다음 변경 가능 시각의 초가 실제로 0이라 이 테스트가 의도한 경계(초 생략 함정)를
        // 실측하고 있음을 스스로 보증한다.
        assertThat(expected.nextChangeableAt()).endsWith(":00+09:00");

        // when & then
        assertThatThrownBy(() -> service.updateNickname(ACCOUNT_ID, "철수"))
                .isInstanceOfSatisfying(BusinessDataException.class,
                        e -> assertThat(e.getData()).isEqualTo(expected));
    }

    @Test
    @DisplayName("[회귀] UTC로는 8/1이지만 KST로는 8/2로 넘어간 순간(2026-08-01T15:30:00Z = "
            + "KST 2026-08-02T00:30:00)에도 기록되는 nicknameChangedAt은 KST 벽시계다 — "
            + "LocalDateTime.now(clock) 대신 시스템 기본 시간대의 LocalDateTime.now()로 되돌리면 이 테스트가 깨진다")
    void updateNickname_success_utcKstDateBoundary_recordsKstWallClockNotUtcDate() {
        // given: UTC 자정을 넘겨 KST 날짜가 하루 앞서가는 순간으로 clock 고정
        Clock boundaryClock = Clock.fixed(Instant.parse("2026-08-01T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        UserProfileEditService service =
                new UserProfileEditService(userAccountRepository, passwordEncoder, authService, boundaryClock);
        UserAccount account = accountWith("길동", "encoded");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then: KST 벽시계는 8/2 00:30:00이다 — UTC 그대로였다면(=시스템 기본 존이 UTC인 파드에서
        // LocalDateTime.now()를 직접 썼다면) 8/1 15:30:00으로 기록됐을 것이다
        assertThat(account.getNicknameChangedAt()).isEqualTo(LocalDateTime.of(2026, 8, 2, 0, 30, 0));
    }

    @Test
    @DisplayName("[회귀] 같은 UTC/KST 날짜 경계에서 쿨다운 중이면 429 응답의 nextChangeableAt도 "
            + "KST 벽시계 기준 날짜로 렌더링된다(UTC 기준이면 하루 앞선 날짜가 나갔을 것이다)")
    void updateNickname_coolingDown_utcKstDateBoundary_nextChangeableAtUsesKstWallClockDate() {
        // given: lastChanged = KST 2026-08-01T00:30:00(경계 하루 전) → nextChangeableAt = KST 2026-08-31T00:30:00
        Clock boundaryClock = Clock.fixed(Instant.parse("2026-08-01T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        UserProfileEditService service =
                new UserProfileEditService(userAccountRepository, passwordEncoder, authService, boundaryClock);
        LocalDateTime lastChanged = LocalDateTime.of(2026, 8, 1, 0, 30, 0);
        UserAccount account = accountWith("길동", "encoded", lastChanged);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> service.updateNickname(ACCOUNT_ID, "철수"))
                .isInstanceOfSatisfying(BusinessDataException.class,
                        e -> assertThat(e.getData())
                                .isEqualTo(new NicknameChangeCooldownResponse("2026-08-31T00:30:00+09:00")));
    }

    // ---------- 비밀번호 변경 ----------

    @Test
    @DisplayName("[USER-PE-21, USER-PE-22, USER-PE-30, USER-PE-35] 현재 비밀번호가 일치하고 신·구가 다르면 "
            + "비밀번호가 인코딩된 새 값으로 교체되고, Clock이 고정한 시각으로 AuthService.issueTokens가 호출되며 "
            + "그 반환값을 그대로 돌려준다")
    void updatePassword_correctCurrentAndDifferentNew_replacesPasswordAndIssuesTokens() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Old1234!", "encoded-old")).willReturn(true);
        given(passwordEncoder.encode("New1234!")).willReturn("encoded-new");
        TokenResponse issued = new TokenResponse("access-token", "refresh-token");
        LocalDateTime expectedNow = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneId.of("Asia/Seoul"));
        given(authService.issueTokens(eq(account), eq(expectedNow))).willReturn(issued);

        // when
        TokenResponse result = service.updatePassword(ACCOUNT_ID, "Old1234!", "New1234!");

        // then
        assertThat(account.getPassword()).isEqualTo("encoded-new");
        assertThat(result).isEqualTo(issued);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(authService).issueTokens(eq(account), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isEqualTo(expectedNow);
    }

    @Test
    @DisplayName("[USER-PE-27, USER-PE-7, USER-PE-31, USER-PE-38] 현재 비밀번호가 저장된 값과 일치하지 않으면 "
            + "INVALID_CURRENT_PASSWORD가 발생하고, 비밀번호는 불변이며 토큰 발급(issueTokens)이 호출되지 않는다")
    void updatePassword_wrongCurrentPassword_throwsAndSkipsTokenIssuance() {
        // given
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Wrong1234!", "encoded-old")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Wrong1234!", "New1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);

        assertThat(account.getPassword()).isEqualTo("encoded-old");
        verify(authService, never()).issueTokens(any(), any());
    }

    @Test
    @DisplayName("[USER-PE-27] currentPassword가 null이면 NPE가 아니라 INVALID_CURRENT_PASSWORD 400으로 흡수되고, "
            + "PasswordEncoder.matches는 호출되지 않는다")
    void updatePassword_nullCurrentPassword_isAbsorbedAsInvalidCurrentPasswordWithoutNpe() {
        // given
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, null, "New1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);

        assertThat(account.getPassword()).isEqualTo("encoded-old");
        verify(passwordEncoder, never()).matches(any(), any());
        verify(authService, never()).issueTokens(any(), any());
    }

    @Test
    @DisplayName("[USER-PE-28, USER-PE-7] 새 비밀번호가 현재 비밀번호와 같으면 SAME_AS_CURRENT_PASSWORD가 발생하고, "
            + "비밀번호는 불변이며 재해싱(PasswordEncoder.encode)조차 호출되지 않는다")
    void updatePassword_sameAsCurrentPassword_throwsWithoutReEncoding() {
        // given
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Same1234!", "encoded-old")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Same1234!", "Same1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_AS_CURRENT_PASSWORD);

        assertThat(account.getPassword()).isEqualTo("encoded-old");
        verify(passwordEncoder, never()).encode(anyString());
        verify(authService, never()).issueTokens(any(), any());
    }

    @Test
    @DisplayName("[USER-PE-29 순서 증거] 현재 비밀번호가 틀리면서 그 값이 newPassword와 우연히 같아도 "
            + "SAME_AS_CURRENT_PASSWORD가 아니라 INVALID_CURRENT_PASSWORD가 발생한다"
            + "(②현재 비밀번호 일치 판정이 ③신·구 동일 판정보다 먼저다)")
    void updatePassword_wrongCurrentPasswordEqualToNewPassword_prefersInvalidCurrentPasswordOverSameAsCurrent() {
        // given: currentPassword == newPassword 이지만 저장된 해시와는 불일치
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Guess1234!", "encoded-old")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Guess1234!", "Guess1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);
    }

    // ---------- 비밀번호 변경: access 토큰 무효화 기준 시각 기록 (USER-ATI-2, 3, 7, 15, 22) ----------

    @Test
    @DisplayName("[USER-ATI-2, USER-ATI-7] 비밀번호 변경이 성공하면 passwordChangedEpochSecond가 NULL이 "
            + "아닌 실제 시각(Instant.now() 기준, 초 단위)으로 기록되고, 그 기록은 authService.issueTokens가 "
            + "호출되는 시점에 이미 반영돼 있다 — 기록이 토큰 발급보다 먼저다(순서가 뒤집히면 방금 발급한 "
            + "토큰이 스스로 무효화된다)")
    void updatePassword_success_recordsBaselineBeforeIssuingTokens() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Old1234!", "encoded-old")).willReturn(true);
        given(passwordEncoder.encode("New1234!")).willReturn("encoded-new");
        long beforeCall = Instant.now().getEpochSecond();

        // issueTokens 호출 시점에 계정의 기준 시각이 이미 기록돼 있는지를 그 순간 스냅샷으로 검증한다
        java.util.concurrent.atomic.AtomicReference<Long> baselineAtIssueTime =
                new java.util.concurrent.atomic.AtomicReference<>();
        given(authService.issueTokens(eq(account), any())).willAnswer(invocation -> {
            baselineAtIssueTime.set(account.getPasswordChangedEpochSecond());
            return new TokenResponse("access-token", "refresh-token");
        });

        // when
        service.updatePassword(ACCOUNT_ID, "Old1234!", "New1234!");
        long afterCall = Instant.now().getEpochSecond();

        // then: 기록값이 issueTokens 호출 이전에 이미 채워져 있어야 하고(순서 증거),
        // 실제 시스템 시계(FIXED_CLOCK이 아니다)로 기록된 값이라 호출 전후 구간 안에 있어야 한다
        assertThat(baselineAtIssueTime.get()).isNotNull();
        assertThat(baselineAtIssueTime.get()).isBetween(beforeCall, afterCall);
        assertThat(account.getPasswordChangedEpochSecond()).isEqualTo(baselineAtIssueTime.get());
    }

    @Test
    @DisplayName("[USER-ATI-3] 현재 비밀번호 불일치로 실패하면 이미 기록돼 있던 passwordChangedEpochSecond가 "
            + "갱신되지 않는다(NULL이던 계정은 계속 NULL)")
    void updatePassword_wrongCurrentPassword_doesNotUpdateBaseline() {
        // given
        UserAccount account = accountWith("길동", "encoded-old");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Wrong1234!", "encoded-old")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Wrong1234!", "New1234!"))
                .isInstanceOf(BusinessException.class);

        assertThat(account.getPasswordChangedEpochSecond()).isNull();
    }

    @Test
    @DisplayName("[USER-ATI-3] 신·구 비밀번호가 같아 SAME_AS_CURRENT_PASSWORD로 실패하면 이미 기록돼 있던 "
            + "passwordChangedEpochSecond가 갱신되지 않는다")
    void updatePassword_sameAsCurrent_doesNotUpdateBaseline() {
        // given: 과거에 이미 한 번 바꾼 적 있는 계정(기준 시각이 NULL이 아님을 함께 증명)
        UserAccount account = accountWith("길동", "encoded-old");
        long previouslyRecorded = 1_700_000_000L;
        account.changePassword("encoded-old", previouslyRecorded);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(passwordEncoder.matches("Same1234!", "encoded-old")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Same1234!", "Same1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SAME_AS_CURRENT_PASSWORD);

        assertThat(account.getPasswordChangedEpochSecond()).isEqualTo(previouslyRecorded);
    }

    @Test
    @DisplayName("[USER-ATI-15] 닉네임 변경 성공은 passwordChangedEpochSecond를 갱신하지 않는다(닉네임 변경으로 "
            + "access 토큰이 무효화되지 않는다는 계약)")
    void updateNickname_success_doesNotTouchPasswordChangedEpochSecond() {
        // given: 비밀번호 변경 이력이 있는 계정(기준 시각이 이미 채워진 상태)에서 닉네임만 바꾼다
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded");
        long recordedBaseline = 1_700_000_000L;
        account.changePassword("encoded", recordedBaseline);
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        given(authService.isNicknameDuplicated("철수")).willReturn(false);

        // when
        service.updateNickname(ACCOUNT_ID, "철수");

        // then
        assertThat(account.getNickname()).isEqualTo("철수");
        assertThat(account.getPasswordChangedEpochSecond()).isEqualTo(recordedBaseline);
    }

    @Test
    @DisplayName("(요구사항 ID 없음, USER-WD 패턴 대응) 존재하지 않는 계정 id로 비밀번호 변경을 시도하면 UNAUTHENTICATED가 발생한다")
    void updatePassword_accountNotFound_throwsUnauthenticated() {
        // given
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userProfileEditService.updatePassword(ACCOUNT_ID, "Old1234!", "New1234!"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("[USER-PE-48] 비밀번호는 쿨다운이 없어 간격 제한 없이 연속 2회 변경해도 둘 다 성공한다 "
            + "(닉네임과 달리 이 경로는 계정의 어떤 시각 컬럼도 읽지 않는다 — 코드 자체가 쿨다운 조회를 하지 않는다)")
    void updatePassword_calledTwiceInARow_bothSucceedWithoutCooldown() {
        // given
        UserProfileEditService service = serviceWithFixedClock();
        UserAccount account = accountWith("길동", "encoded-original");
        given(userAccountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
        TokenResponse issuedFirst = new TokenResponse("access-1", "refresh-1");
        TokenResponse issuedSecond = new TokenResponse("access-2", "refresh-2");

        // 1회차: encoded-original -> encoded-first
        given(passwordEncoder.matches("Old1234!", "encoded-original")).willReturn(true);
        given(passwordEncoder.encode("New1111!")).willReturn("encoded-first");
        given(authService.issueTokens(eq(account), any())).willReturn(issuedFirst);

        // when: 1회차
        TokenResponse firstResult = service.updatePassword(ACCOUNT_ID, "Old1234!", "New1111!");

        // then: 1회차 성공
        assertThat(firstResult).isEqualTo(issuedFirst);
        assertThat(account.getPassword()).isEqualTo("encoded-first");

        // given: 2회차는 방금 바뀐 비밀번호를 현재 비밀번호로 삼는다(간격 제한이 있다면 여기서 막혀야 한다)
        given(passwordEncoder.matches("New1111!", "encoded-first")).willReturn(true);
        given(passwordEncoder.encode("New2222!")).willReturn("encoded-second");
        given(authService.issueTokens(eq(account), any())).willReturn(issuedSecond);

        // when: 2회차(간격 없이 곧바로)
        TokenResponse secondResult = service.updatePassword(ACCOUNT_ID, "New1111!", "New2222!");

        // then: 2회차도 429 없이 성공
        assertThat(secondResult).isEqualTo(issuedSecond);
        assertThat(account.getPassword()).isEqualTo("encoded-second");
    }
}
