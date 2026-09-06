package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#changeProfileImgUrl(String)}과 그 선행 상태(신규 계정의 초기값)만 다루는 순수 단위
 * 테스트(Spring 컨텍스트/DB 없음). 요구사항: {@code docs/requirements/user/profile-image.md}.
 *
 * <p><b>DB 전략 관련 결정</b>: {@code profile_img_url} 컬럼의 DDL 자체(길이·NULL 허용·기본값)는 실제
 * MySQL 스키마 확인이 필요해 이 테스트로 증명되지 않는다({@code UserAccountNicknameChangeTest}와 같은
 * 이유). 여기서는 <b>애플리케이션이 만드는 신규 인스턴스·엔티티 메서드의 동작</b>만 다룬다.
 */
class UserAccountProfileImageChangeTest {

    private UserAccount newAccount() {
        return UserAccount.builder()
                .nickname("길동")
                .password("encoded-password")
                .build();
    }

    @Test
    @DisplayName("[USER-PI-51] builder()로 새 계정을 만들면 profileImgUrl은 NULL이다"
            + "(@Builder가 이 필드를 파라미터로 받지 않아 가입은 컬럼을 채우지 않는다)")
    void builder_newAccount_hasNullProfileImgUrl() {
        // when
        UserAccount account = newAccount();

        // then
        assertThat(account.getProfileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[USER-PI-11, PI-53] changeProfileImgUrl을 호출하면 그 값으로 컬럼이 바뀐다")
    void changeProfileImgUrl_updatesColumnValue() {
        // given
        UserAccount account = newAccount();

        // when
        account.changeProfileImgUrl("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");

        // then
        assertThat(account.getProfileImgUrl())
                .isEqualTo("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }

    @Test
    @DisplayName("[USER-PI-70 계열] changeProfileImgUrl을 다시 호출하면 새 값으로 덮어쓰고, 직전 값은 "
            + "호출 이후 이 인스턴스 어디에도 남지 않는다(옛 값을 지우려는 호출자는 부르기 전에 챙겨야 "
            + "한다는 AccountProfileImageWriter의 전제와 일치)")
    void changeProfileImgUrl_calledAgain_overwritesAndLosesPreviousValue() {
        // given
        UserAccount account = newAccount();
        account.changeProfileImgUrl("user-profile-img/first-aaaa-4bbb-8ccc-1234567890ab.jpg");

        // when
        account.changeProfileImgUrl("user-profile-img/second-aaaa-4bbb-8ccc-1234567890ab.jpg");

        // then
        assertThat(account.getProfileImgUrl())
                .isEqualTo("user-profile-img/second-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }
}
