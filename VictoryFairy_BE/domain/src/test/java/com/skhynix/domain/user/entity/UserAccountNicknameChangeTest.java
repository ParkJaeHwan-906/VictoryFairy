package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#changeNickname(String, LocalDateTime)}과 그 선행 상태(신규 계정의 초기값)만 다루는
 * 순수 단위 테스트(Spring 컨텍스트/DB 없음). 요구사항: {@code docs/requirements/user/profile-edit.md}
 * (USER-PE-41, 45, 46).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code nickname_changed_at} 컬럼의 DDL 자체(USER-PE-40, 타입·
 * NULL 허용·기본값)는 실제 MySQL 스키마 확인이 필요해 이 테스트로 증명되지 않는다({@code UserAccountPointTest}와
 * 같은 이유로 이 모듈에 H2/Testcontainers/구동 중인 MySQL이 없음). 여기서는 <b>애플리케이션이 만드는 신규
 * 인스턴스·엔티티 메서드의 동작</b>만 다룬다.
 */
class UserAccountNicknameChangeTest {

    private UserAccount newAccount() {
        return UserAccount.builder()
                .nickname("길동")
                .password("encoded-password")
                .build();
    }

    @Test
    @DisplayName("[USER-PE-46] builder()로 새 계정을 만들면 nicknameChangedAt은 NULL이다"
            + "(@Builder가 이 필드를 파라미터로 받지 않아 가입은 컬럼을 채우지 않는다)")
    void builder_newAccount_hasNullNicknameChangedAt() {
        // when
        UserAccount account = newAccount();

        // then
        assertThat(account.getNicknameChangedAt()).isNull();
    }

    @Test
    @DisplayName("[USER-PE-41, USER-PE-8] changeNickname을 호출하면 닉네임과 변경 시각이 함께 갱신된다"
            + "(같은 전이 안에서 둘 다 바뀐다 — 한쪽만 바뀌는 경로가 구조적으로 없다)")
    void changeNickname_updatesNicknameAndRecordsChangedAtTogether() {
        // given
        UserAccount account = newAccount();
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 17, 12, 0, 0);

        // when
        account.changeNickname("철수", changedAt);

        // then
        assertThat(account.getNickname()).isEqualTo("철수");
        assertThat(account.getNicknameChangedAt()).isEqualTo(changedAt);
    }

    @Test
    @DisplayName("[USER-PE-41] 이미 기록된 변경 시각이 있어도 다시 changeNickname을 호출하면 새 값으로 덮어쓴다")
    void changeNickname_calledAgain_overwritesPreviouslyRecordedChangedAt() {
        // given
        UserAccount account = newAccount();
        account.changeNickname("철수", LocalDateTime.of(2026, 1, 1, 0, 0, 0));

        // when
        LocalDateTime latest = LocalDateTime.of(2026, 2, 1, 0, 0, 0);
        account.changeNickname("영희", latest);

        // then
        assertThat(account.getNickname()).isEqualTo("영희");
        assertThat(account.getNicknameChangedAt()).isEqualTo(latest);
    }
}
