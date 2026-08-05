package com.skhynix.domain.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserSupportPlayer}의 Builder 필드 배선과 전이 메서드({@code oppose}/{@code support})만
 * 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@link UserSupportTeamTest} javadoc과 동일한 사유로
 * {@code @DataJpaTest} 라운드트립은 다루지 않는다. {@code user_account_id}/{@code player_id} FK
 * 제약, {@code (user_account_id, player_id)} UNIQUE 제약은 이 테스트로 검증되지 않는다.
 */
class UserSupportPlayerTest {

    private Player newPlayer(String name) {
        Team team = Team.builder().name("두산 베어스").build();
        return Player.builder().team(team).name(name).average(0.0).build();
    }

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("password1!").build();
    }

    @Test
    @DisplayName("Builder(userAccount, player)로 생성하면 필드가 동일 인스턴스로 배선되고 oppose는 null(응원 중)로 초기화된다")
    void builder_wiresFieldsToSameInstanceAndInitializesOpposeAsNull() {
        // given
        Player player = newPlayer("김선수");
        UserAccount userAccount = newUserAccount("팬1");

        // when
        UserSupportPlayer userSupportPlayer =
                UserSupportPlayer.builder().userAccount(userAccount).player(player).build();

        // then
        assertThat(userSupportPlayer.getUserAccount()).isSameAs(userAccount);
        assertThat(userSupportPlayer.getPlayer()).isSameAs(player);
        assertThat(userSupportPlayer.getOppose()).isNull();
        assertThat(userSupportPlayer.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("oppose(시각)을 호출하면 해당 시각이 기록되고 isOpposed()가 true가 된다")
    void oppose_setsOpposeTimeAndMarksAsOpposed() {
        // given
        UserSupportPlayer userSupportPlayer = UserSupportPlayer.builder()
                .userAccount(newUserAccount("팬2"))
                .player(newPlayer("이선수"))
                .build();
        LocalDateTime opposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);

        // when
        userSupportPlayer.oppose(opposedAt);

        // then
        assertThat(userSupportPlayer.getOppose()).isEqualTo(opposedAt);
        assertThat(userSupportPlayer.isOpposed()).isTrue();
    }

    @Test
    @DisplayName("이미 응원 취소된 상태에서 다른 시각으로 oppose()를 다시 호출해도 최초 취소 시각이 보존된다(no-op)")
    void oppose_isNoOp_whenAlreadyOpposed() {
        // given
        UserSupportPlayer userSupportPlayer = UserSupportPlayer.builder()
                .userAccount(newUserAccount("팬3"))
                .player(newPlayer("박선수"))
                .build();
        LocalDateTime firstOpposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondOpposedAt = LocalDateTime.of(2026, 7, 21, 10, 0);
        userSupportPlayer.oppose(firstOpposedAt);

        // when
        userSupportPlayer.oppose(secondOpposedAt);

        // then
        assertThat(userSupportPlayer.getOppose()).isEqualTo(firstOpposedAt);
    }

    @Test
    @DisplayName("support()를 호출하면 oppose가 null로 되돌아가고 isOpposed()가 false가 된다(재응원)")
    void support_resetsOpposeToNullAndMarksAsNotOpposed() {
        // given
        UserSupportPlayer userSupportPlayer = UserSupportPlayer.builder()
                .userAccount(newUserAccount("팬4"))
                .player(newPlayer("최선수"))
                .build();
        userSupportPlayer.oppose(LocalDateTime.of(2026, 7, 20, 10, 0));

        // when
        userSupportPlayer.support();

        // then
        assertThat(userSupportPlayer.getOppose()).isNull();
        assertThat(userSupportPlayer.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("응원 중인 상태에서 support()를 호출해도 그대로 응원 중이다(멱등)")
    void support_isIdempotent_whenAlreadySupporting() {
        // given
        UserSupportPlayer userSupportPlayer = UserSupportPlayer.builder()
                .userAccount(newUserAccount("팬5"))
                .player(newPlayer("정선수"))
                .build();

        // when
        userSupportPlayer.support();

        // then
        assertThat(userSupportPlayer.getOppose()).isNull();
        assertThat(userSupportPlayer.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("oppose -> support -> oppose 왕복 후 마지막 oppose 호출의 시각이 새로 기록된다")
    void oppose_recordsNewTimestamp_afterSupportResetsPreviousOppose() {
        // given
        UserSupportPlayer userSupportPlayer = UserSupportPlayer.builder()
                .userAccount(newUserAccount("팬6"))
                .player(newPlayer("한선수"))
                .build();
        LocalDateTime firstOpposedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondOpposedAt = LocalDateTime.of(2026, 7, 22, 15, 30);
        userSupportPlayer.oppose(firstOpposedAt);
        userSupportPlayer.support();

        // when
        userSupportPlayer.oppose(secondOpposedAt);

        // then
        assertThat(userSupportPlayer.getOppose()).isEqualTo(secondOpposedAt);
        assertThat(userSupportPlayer.isOpposed()).isTrue();
    }
}
