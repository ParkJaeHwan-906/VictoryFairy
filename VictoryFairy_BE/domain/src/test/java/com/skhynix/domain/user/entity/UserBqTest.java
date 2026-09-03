package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserBq}의 Builder 필드 배선과 {@code bqScore} 초기값만 검증하는 순수 단위 테스트
 * (Spring 컨텍스트/DB 없음). 요구사항: {@code docs/requirements/user/me-profile.md}
 * (USER-ME-3 ~ 6 — 선행 스키마의 애플리케이션 계약 측면).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code users_bq} 테이블 DDL(컬럼 구성 USER-ME-3, FK CASCADE USER-ME-4,
 * UNIQUE(user_account_id) USER-ME-5, 타임스탬프 갱신 USER-ME-6)은 실제 MySQL 라운드트립이 필요해 이
 * 테스트로 증명되지 않는다({@code UserSupportTeamTest}·{@code StadiumTest}와 같은 이유로 이 모듈에
 * H2/Testcontainers/구동 중인 MySQL이 없음). 여기서는 <b>애플리케이션이 만드는 신규 인스턴스</b>의
 * 필드 배선과 {@code bqScore} 초기값만 다룬다.
 */
class UserBqTest {

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("encoded-password").build();
    }

    @Test
    @DisplayName("[USER-ME-23 관련] builder(userAccount)로 생성하면 userAccount가 동일 인스턴스로 배선되고 "
            + "bqScore는 0에서 시작하며, 영속화 전이므로 id/생성·수정시각은 null이다")
    void builder_wiresUserAccountToSameInstance_andInitializesBqScoreToZero() {
        // given
        UserAccount userAccount = newUserAccount("nickname");

        // when
        UserBq userBq = UserBq.builder().userAccount(userAccount).build();

        // then
        assertThat(userBq.getUserAccount()).isSameAs(userAccount);
        assertThat(userBq.getBqScore()).isZero();
        assertThat(userBq.getId()).isNull();
        assertThat(userBq.getCreatedAt()).isNull();
        assertThat(userBq.getUpdatedAt()).isNull();
    }

    // ---------- addBqScore — QUIZ-PBQ-16·19의 엔티티 측 근거 ----------
    // (docs/requirements/quiz/quiz-point-bq-split.md, quiz 모듈의 QuizSubmitService가 유일한 호출자)

    @Test
    @DisplayName("[QUIZ-PBQ-16] 양수 delta는 bqScore에 그대로 더해진다")
    void addBqScore_positiveDelta_addsToScore() {
        // given
        UserBq userBq = UserBq.builder().userAccount(newUserAccount("nickname")).build();

        // when
        userBq.addBqScore(3L);
        userBq.addBqScore(2L);

        // then
        assertThat(userBq.getBqScore()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-19] delta가 0이면 bqScore가 그대로다(적립 없음)")
    void addBqScore_zeroDelta_doesNotChangeScore() {
        // given
        UserBq userBq = UserBq.builder().userAccount(newUserAccount("nickname")).build();
        userBq.addBqScore(5L);

        // when
        userBq.addBqScore(0L);

        // then
        assertThat(userBq.getBqScore()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-19] delta가 음수여도 예외 없이 무시되고 bqScore가 그대로다")
    void addBqScore_negativeDelta_isIgnoredWithoutException() {
        // given
        UserBq userBq = UserBq.builder().userAccount(newUserAccount("nickname")).build();
        userBq.addBqScore(5L);

        // when
        userBq.addBqScore(-1L);

        // then
        assertThat(userBq.getBqScore()).isEqualTo(5L);
    }
}
