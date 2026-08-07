package com.skhynix.domain.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserSupportTeam}의 Builder 필드 배선과 전이 메서드({@code oppose}/{@code support})만
 * 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code domain} 모듈에 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers/구동 중인 MySQL이 전혀 없어({@link com.skhynix.domain.stadium.entity.StadiumTest}
 * javadoc 참고) 실제 저장·조회 라운드트립은 다루지 않는다. {@code user_account_id}/{@code team_id} FK
 * 제약, {@code (user_account_id, team_id)} UNIQUE 제약은 이 테스트로 검증되지 않는다.
 */
class UserSupportTeamTest {

    private Team newTeam(String name) {
        return Team.builder().name(name).build();
    }

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("password1!").build();
    }

    @Test
    @DisplayName("Builder(userAccount, team)로 생성하면 필드가 동일 인스턴스로 배선되고 oppose는 null(응원 중)로 초기화된다")
    void builder_wiresFieldsToSameInstanceAndInitializesOpposeAsNull() {
        // given
        Team team = newTeam("두산 베어스");
        UserAccount userAccount = newUserAccount("팬1");

        // when
        UserSupportTeam userSupportTeam =
                UserSupportTeam.builder().userAccount(userAccount).team(team).build();

        // then
        assertThat(userSupportTeam.getUserAccount()).isSameAs(userAccount);
        assertThat(userSupportTeam.getTeam()).isSameAs(team);
        assertThat(userSupportTeam.getOppose()).isNull();
        assertThat(userSupportTeam.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("oppose(시각)을 호출하면 해당 시각이 기록되고 isOpposed()가 true가 된다")
    void oppose_setsOpposeTimeAndMarksAsOpposed() {
        // given
        UserSupportTeam userSupportTeam = UserSupportTeam.builder()
                .userAccount(newUserAccount("팬2"))
                .team(newTeam("KT 위즈"))
                .build();
        LocalDateTime opposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);

        // when
        userSupportTeam.oppose(opposedAt);

        // then
        assertThat(userSupportTeam.getOppose()).isEqualTo(opposedAt);
        assertThat(userSupportTeam.isOpposed()).isTrue();
    }

    @Test
    @DisplayName("이미 응원 취소된 상태에서 다른 시각으로 oppose()를 다시 호출해도 최초 취소 시각이 보존된다(no-op)")
    void oppose_isNoOp_whenAlreadyOpposed() {
        // given
        UserSupportTeam userSupportTeam = UserSupportTeam.builder()
                .userAccount(newUserAccount("팬3"))
                .team(newTeam("LG 트윈스"))
                .build();
        LocalDateTime firstOpposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondOpposedAt = LocalDateTime.of(2026, 7, 21, 10, 0);
        userSupportTeam.oppose(firstOpposedAt);

        // when
        userSupportTeam.oppose(secondOpposedAt);

        // then
        assertThat(userSupportTeam.getOppose()).isEqualTo(firstOpposedAt);
    }

    @Test
    @DisplayName("support()를 호출하면 oppose가 null로 되돌아가고 isOpposed()가 false가 된다(재응원)")
    void support_resetsOpposeToNullAndMarksAsNotOpposed() {
        // given
        UserSupportTeam userSupportTeam = UserSupportTeam.builder()
                .userAccount(newUserAccount("팬4"))
                .team(newTeam("KIA 타이거즈"))
                .build();
        userSupportTeam.oppose(LocalDateTime.of(2026, 7, 20, 10, 0));

        // when
        userSupportTeam.support();

        // then
        assertThat(userSupportTeam.getOppose()).isNull();
        assertThat(userSupportTeam.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("응원 중인 상태에서 support()를 호출해도 그대로 응원 중이다(멱등)")
    void support_isIdempotent_whenAlreadySupporting() {
        // given
        UserSupportTeam userSupportTeam = UserSupportTeam.builder()
                .userAccount(newUserAccount("팬5"))
                .team(newTeam("삼성 라이온즈"))
                .build();

        // when
        userSupportTeam.support();

        // then
        assertThat(userSupportTeam.getOppose()).isNull();
        assertThat(userSupportTeam.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("oppose -> support -> oppose 왕복 후 마지막 oppose 호출의 시각이 새로 기록된다")
    void oppose_recordsNewTimestamp_afterSupportResetsPreviousOppose() {
        // given
        UserSupportTeam userSupportTeam = UserSupportTeam.builder()
                .userAccount(newUserAccount("팬6"))
                .team(newTeam("롯데 자이언츠"))
                .build();
        LocalDateTime firstOpposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondOpposedAt = LocalDateTime.of(2026, 7, 22, 15, 30);
        userSupportTeam.oppose(firstOpposedAt);
        userSupportTeam.support();

        // when
        userSupportTeam.oppose(secondOpposedAt);

        // then
        assertThat(userSupportTeam.getOppose()).isEqualTo(secondOpposedAt);
        assertThat(userSupportTeam.isOpposed()).isTrue();
    }
}
