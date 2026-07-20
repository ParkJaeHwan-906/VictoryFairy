package com.skhynix.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Chatroom}의 Builder 필드 배선과 전이 메서드({@code join}/{@code leave}/{@code delete})만
 * 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code domain} 모듈에 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers/구동 중인 MySQL이 전혀 없어({@link com.skhynix.domain.stadium.entity.StadiumTest}
 * javadoc 참고) 실제 저장·조회 라운드트립은 다루지 않는다. {@code team_id}/{@code owner_account_id} FK
 * 제약, {@code uid} unique 제약, 컬럼 매핑은 이 테스트로 검증되지 않는다.
 */
class ChatroomTest {

    private Team newTeam(String name) {
        return Team.builder().name(name).build();
    }

    private UserAccount newOwner(String nickname) {
        User user = User.builder()
                .name("소유자")
                .tel("01000000000")
                .email(nickname + "@example.com")
                .gender(Gender.MALE)
                .build();
        return UserAccount.builder().user(user).nickname(nickname).password("password").build();
    }

    @Test
    @DisplayName("Builder(team, owner, name)로 생성하면 필드가 배선되고 participants=0, deletedAt=null, uid는 null이 아닌 값으로 초기화된다")
    void builder_withTeamOwnerAndName_initializesParticipantsZeroAndNoDeletion() {
        // given
        Team team = newTeam("두산 베어스");
        UserAccount owner = newOwner("owner1");

        // when
        Chatroom chatroom =
                Chatroom.builder().team(team).owner(owner).name("두산 베어스 채팅방").build();

        // then
        assertThat(chatroom.getTeam()).isSameAs(team);
        assertThat(chatroom.getName()).isEqualTo("두산 베어스 채팅방");
        assertThat(chatroom.getParticipants()).isZero();
        assertThat(chatroom.getDeletedAt()).isNull();
        assertThat(chatroom.isDeleted()).isFalse();
        assertThat(chatroom.getUid()).isNotNull();
    }

    @Test
    @DisplayName("Builder로 생성 시 getOwner()가 전달한 UserAccount와 동일 인스턴스로 배선된다")
    void builder_wiresOwnerToSameInstance() {
        // given
        Team team = newTeam("KT 위즈");
        UserAccount owner = newOwner("owner-wiring");

        // when
        Chatroom chatroom = Chatroom.builder().team(team).owner(owner).name("방").build();

        // then
        assertThat(chatroom.getOwner()).isSameAs(owner);
    }

    @Test
    @DisplayName("uid는 생성할 때마다 서로 다른 값이 부여된다")
    void builder_generatesDistinctUidPerInstance() {
        // given
        Team team = newTeam("LG 트윈스");
        UserAccount owner = newOwner("owner2");

        // when
        Chatroom first = Chatroom.builder().team(team).owner(owner).name("방1").build();
        Chatroom second = Chatroom.builder().team(team).owner(owner).name("방2").build();

        // then
        assertThat(first.getUid()).isNotEqualTo(second.getUid());
    }

    @Test
    @DisplayName("join()을 호출하면 participants가 1 증가한다")
    void join_incrementsParticipantsByOne() {
        // given
        Chatroom chatroom = Chatroom.builder()
                .team(newTeam("KIA 타이거즈"))
                .owner(newOwner("owner3"))
                .name("방")
                .build();

        // when
        chatroom.join();

        // then
        assertThat(chatroom.getParticipants()).isEqualTo(1);
    }

    @Test
    @DisplayName("participants가 있는 상태에서 leave()를 호출하면 1 감소한다")
    void leave_decrementsParticipantsByOne_whenParticipantsExist() {
        // given
        Chatroom chatroom = Chatroom.builder()
                .team(newTeam("삼성 라이온즈"))
                .owner(newOwner("owner4"))
                .name("방")
                .build();
        chatroom.join();
        chatroom.join();

        // when
        chatroom.leave();

        // then
        assertThat(chatroom.getParticipants()).isEqualTo(1);
    }

    @Test
    @DisplayName("participants가 0인 상태에서 leave()를 호출해도 음수로 내려가지 않고 0을 유지한다")
    void leave_doesNotGoBelowZero_whenParticipantsAlreadyZero() {
        // given
        Chatroom chatroom = Chatroom.builder()
                .team(newTeam("롯데 자이언츠"))
                .owner(newOwner("owner5"))
                .name("방")
                .build();

        // when
        chatroom.leave();

        // then
        assertThat(chatroom.getParticipants()).isZero();
    }

    @Test
    @DisplayName("delete(시각)을 호출하면 deletedAt이 채워지고 isDeleted()가 true가 된다")
    void delete_setsDeletedAtAndMarksAsDeleted() {
        // given
        Chatroom chatroom = Chatroom.builder()
                .team(newTeam("한화 이글스"))
                .owner(newOwner("owner6"))
                .name("방")
                .build();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 7, 20, 10, 0);

        // when
        chatroom.delete(deletedAt);

        // then
        assertThat(chatroom.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(chatroom.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("이미 삭제된 채팅방에 다른 시각으로 delete()를 다시 호출해도 최초 삭제 시각이 보존된다(no-op)")
    void delete_isNoOp_whenAlreadyDeleted() {
        // given
        Chatroom chatroom = Chatroom.builder()
                .team(newTeam("NC 다이노스"))
                .owner(newOwner("owner7"))
                .name("방")
                .build();
        LocalDateTime firstDeletedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondDeletedAt = LocalDateTime.of(2026, 7, 21, 10, 0);
        chatroom.delete(firstDeletedAt);

        // when
        chatroom.delete(secondDeletedAt);

        // then
        assertThat(chatroom.getDeletedAt()).isEqualTo(firstDeletedAt);
    }
}
