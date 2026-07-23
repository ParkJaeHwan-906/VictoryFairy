package com.skhynix.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Chat}의 Builder 필드 배선과 전이 메서드({@code blind}/{@code unblind}/{@code delete})만
 * 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@link ChatroomTest} javadoc과 동일한 사유로 {@code @DataJpaTest}
 * 라운드트립은 다루지 않는다. {@code chatroom_id}/{@code user_account_id} FK 제약, {@code content}
 * TEXT 컬럼 매핑은 이 테스트로 검증되지 않는다. {@link UserAccount}는 {@code User}를 optional=false로
 * 참조하지만 DB 제약이라 인메모리 배선 검증에는 영향이 없어 최소 생성만 사용한다.
 */
class ChatTest {

    private Chatroom newChatroom(String name) {
        return Chatroom.builder().team(Team.builder().name("두산 베어스").build()).name(name).build();
    }

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("password1!").build();
    }

    @Test
    @DisplayName("Builder(chatroom, userAccount, content)로 생성하면 필드가 배선되고 blind=false, deletedAt=null로 초기화된다")
    void builder_withRequiredFields_initializesBlindFalseAndNoDeletion() {
        // given
        Chatroom chatroom = newChatroom("두산 베어스 채팅방");
        UserAccount userAccount = newUserAccount("두산팬1");

        // when
        Chat chat = Chat.builder().chatroom(chatroom).userAccount(userAccount).content("안녕하세요").build();

        // then
        assertThat(chat.getChatroom()).isSameAs(chatroom);
        assertThat(chat.getUserAccount()).isSameAs(userAccount);
        assertThat(chat.getContent()).isEqualTo("안녕하세요");
        assertThat(chat.isBlind()).isFalse();
        assertThat(chat.getDeletedAt()).isNull();
        assertThat(chat.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("blind()를 호출하면 blind가 true로 바뀌고, unblind()를 호출하면 다시 false로 바뀐다")
    void blindAndUnblind_toggleBlindFlag() {
        // given
        Chat chat = Chat.builder()
                .chatroom(newChatroom("방"))
                .userAccount(newUserAccount("유저"))
                .content("내용")
                .build();

        // when
        chat.blind();

        // then
        assertThat(chat.isBlind()).isTrue();

        // when
        chat.unblind();

        // then
        assertThat(chat.isBlind()).isFalse();
    }

    @Test
    @DisplayName("delete(시각)을 호출하면 deletedAt이 채워지고 isDeleted()가 true가 된다")
    void delete_setsDeletedAtAndMarksAsDeleted() {
        // given
        Chat chat = Chat.builder()
                .chatroom(newChatroom("방"))
                .userAccount(newUserAccount("유저"))
                .content("내용")
                .build();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 7, 20, 10, 0);

        // when
        chat.delete(deletedAt);

        // then
        assertThat(chat.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(chat.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("이미 삭제된 메시지에 다른 시각으로 delete()를 다시 호출해도 최초 삭제 시각이 보존된다(no-op)")
    void delete_isNoOp_whenAlreadyDeleted() {
        // given
        Chat chat = Chat.builder()
                .chatroom(newChatroom("방"))
                .userAccount(newUserAccount("유저"))
                .content("내용")
                .build();
        LocalDateTime firstDeletedAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime secondDeletedAt = LocalDateTime.of(2026, 7, 21, 10, 0);
        chat.delete(firstDeletedAt);

        // when
        chat.delete(secondDeletedAt);

        // then
        assertThat(chat.getDeletedAt()).isEqualTo(firstDeletedAt);
    }
}
