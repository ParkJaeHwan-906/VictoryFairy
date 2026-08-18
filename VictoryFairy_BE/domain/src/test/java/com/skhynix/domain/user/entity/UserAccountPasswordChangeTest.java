package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#changePassword(String, long)}과 무효화 판정의 단일 출처
 * {@link UserAccount#acceptsTokenIssuedAt(Long, long)}만 다루는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * 요구사항: {@code docs/requirements/user/access-token-invalidation.md}(USER-ATI-1~22).
 *
 * <p>{@link com.skhynix.domain.user.entity.UserAccountNicknameChangeTest}와 대칭 구조 —
 * {@code changeNickname}/{@code changePassword}가 "교체+시각 기록을 한 전이로 묶는다"는 같은 설계를
 * 공유하므로 검증 축도 같다(신규 계정 초기값 NULL, 전이 후 값, 재호출 시 덮어쓰기).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code password_changed_epoch_second} 컬럼의 DDL 자체(타입·NULL 허용)는
 * 실제 MySQL 스키마 확인이 필요해 이 테스트로 증명되지 않는다(H2/Testcontainers 부재, 다른 domain 엔티티
 * 테스트들과 같은 제약). 여기서는 <b>애플리케이션이 만드는 신규 인스턴스·엔티티 메서드의 동작</b>만 다룬다.
 */
class UserAccountPasswordChangeTest {

    private UserAccount newAccount() {
        return UserAccount.builder()
                .nickname("길동")
                .password("encoded-old")
                .build();
    }

    // ---------- 기록 (USER-ATI-1, 9) ----------

    @Test
    @DisplayName("[USER-ATI-1, USER-ATI-9] builder()로 새 계정을 만들면 passwordChangedEpochSecond는 NULL이다"
            + "(@Builder가 이 필드를 파라미터로 받지 않아 가입은 컬럼을 채우지 않는다 — 백필하지 않는 정책의 근거)")
    void builder_newAccount_hasNullPasswordChangedEpochSecond() {
        // when
        UserAccount account = newAccount();

        // then
        assertThat(account.getPasswordChangedEpochSecond()).isNull();
    }

    @Test
    @DisplayName("[USER-ATI-2] changePassword를 호출하면 비밀번호와 기준 시각(epoch 초)이 함께 갱신된다"
            + "(같은 전이 안에서 둘 다 바뀐다 — 한쪽만 바뀌는 경로가 구조적으로 없다)")
    void changePassword_updatesPasswordAndRecordsEpochSecondTogether() {
        // given
        UserAccount account = newAccount();
        long epochSecond = 1_755_400_000L;

        // when
        account.changePassword("encoded-new", epochSecond);

        // then
        assertThat(account.getPassword()).isEqualTo("encoded-new");
        assertThat(account.getPasswordChangedEpochSecond()).isEqualTo(epochSecond);
    }

    @Test
    @DisplayName("이미 기록된 기준 시각이 있어도 다시 changePassword를 호출하면 새 값으로 덮어쓴다")
    void changePassword_calledAgain_overwritesPreviouslyRecordedEpochSecond() {
        // given
        UserAccount account = newAccount();
        account.changePassword("encoded-1", 1_000_000L);

        // when
        account.changePassword("encoded-2", 2_000_000L);

        // then
        assertThat(account.getPassword()).isEqualTo("encoded-2");
        assertThat(account.getPasswordChangedEpochSecond()).isEqualTo(2_000_000L);
    }

    // ---------- 대조 규칙 static acceptsTokenIssuedAt(Long, long) — 무효화 판정의 단일 출처 ----------

    @Test
    @DisplayName("[USER-ATI-9] 기준 시각(baseline)이 NULL이면 iat가 무엇이든(과거·미래 불문) 항상 통과한다"
            + "(백필하지 않는 기존 계정은 무효화 대조를 건너뛴다)")
    void acceptsTokenIssuedAt_nullBaseline_alwaysAccepts() {
        assertThat(UserAccount.acceptsTokenIssuedAt(null, 0L)).isTrue();
        assertThat(UserAccount.acceptsTokenIssuedAt(null, Long.MIN_VALUE)).isTrue();
        assertThat(UserAccount.acceptsTokenIssuedAt(null, 9_999_999_999L)).isTrue();
    }

    @Test
    @DisplayName("[USER-ATI-8, USER-ATI-7, USER-ATI-22] iat가 기준 시각과 정확히 같은 초이면 통과한다"
            + "(같은 초는 유효 — 이 경계가 깨지면 비밀번호 변경 응답으로 준 새 토큰이 자기 자신에게 거부된다)")
    void acceptsTokenIssuedAt_sameSecondAsBaseline_accepts() {
        // given
        long baseline = 1_755_400_000L;

        // when & then
        assertThat(UserAccount.acceptsTokenIssuedAt(baseline, baseline)).isTrue();
    }

    @Test
    @DisplayName("[USER-ATI-4, USER-ATI-8] iat가 기준 시각보다 1초 앞서면(baseline-1) 거부한다")
    void acceptsTokenIssuedAt_oneSecondBeforeBaseline_rejects() {
        // given
        long baseline = 1_755_400_000L;

        // when & then
        assertThat(UserAccount.acceptsTokenIssuedAt(baseline, baseline - 1)).isFalse();
    }

    @Test
    @DisplayName("[USER-ATI-8] iat가 기준 시각보다 1초 뒤이면(baseline+1) 통과한다")
    void acceptsTokenIssuedAt_oneSecondAfterBaseline_accepts() {
        // given
        long baseline = 1_755_400_000L;

        // when & then
        assertThat(UserAccount.acceptsTokenIssuedAt(baseline, baseline + 1)).isTrue();
    }

    @Test
    @DisplayName("[USER-ATI-21] 기준 시각이 미래로 기록돼 있으면, 새로 발급된(현재에 가까운) iat라도 그 기준 "
            + "시각보다 앞서는 한 계속 거부된다(fail-closed — 미래 값을 보정하지 않는다)")
    void acceptsTokenIssuedAt_futureBaseline_stillRejectsRecentIssuedAt() {
        // given: 기준 시각이 아주 먼 미래로 잘못 기록된 상황
        long farFutureBaseline = 9_999_999_999L;
        long recentIssuedAt = 1_755_400_000L;

        // when & then
        assertThat(UserAccount.acceptsTokenIssuedAt(farFutureBaseline, recentIssuedAt)).isFalse();
    }

    // ---------- 인스턴스 오버로드 acceptsTokenIssuedAt(long) ----------

    @Test
    @DisplayName("인스턴스 메서드 acceptsTokenIssuedAt(long)은 이 계정의 passwordChangedEpochSecond를 기준으로 "
            + "static 규칙에 위임한다(엔티티를 이미 손에 쥔 refresh 재발급 호출자용)")
    void instanceAcceptsTokenIssuedAt_delegatesToStaticRuleUsingOwnField() {
        // given
        UserAccount account = newAccount();
        long baseline = 1_755_400_000L;
        account.changePassword("encoded-new", baseline);

        // when & then: 정확히 static과 동일한 세 경계
        assertThat(account.acceptsTokenIssuedAt(baseline)).isTrue();
        assertThat(account.acceptsTokenIssuedAt(baseline - 1)).isFalse();
        assertThat(account.acceptsTokenIssuedAt(baseline + 1)).isTrue();
    }

    @Test
    @DisplayName("changePassword를 한 번도 호출하지 않은 신규 계정은 인스턴스 메서드도 항상 true를 반환한다"
            + "(passwordChangedEpochSecond가 NULL이라 static의 null 규칙을 그대로 물려받는다)")
    void instanceAcceptsTokenIssuedAt_nullField_alwaysAccepts() {
        // given
        UserAccount account = newAccount();

        // when & then
        assertThat(account.acceptsTokenIssuedAt(Long.MIN_VALUE)).isTrue();
    }
}
