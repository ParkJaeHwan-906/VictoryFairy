package com.skhynix.quiz.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageEvent;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.profanity.ProfanityDataLoader;
import com.skhynix.quiz.chat.profanity.ProfanityFilter;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 전송 경로에 <b>진짜 {@link ProfanityFilter}</b>(실제 JSON 데이터 포함)를 물려 종단 결과를 고정한다.
 *
 * <p>{@code ChatServiceTest}는 필터를 목으로 두고 "결과가 저장·응답·SSE 세 곳에 같은 문자열로 흐르는가"라는
 * 배선만 본다. 그 방식으로는 배선이 맞아도 <b>실제로 무엇이 저장되는지</b>는 알 수 없어서, 여기서 한 번은
 * 진짜 필터를 태운다(QUIZ-CPF-1/2/3). 리포지토리·발행기만 목이다.
 *
 * <p>기댓값 {@code "두산"}은 {@code teams.code = "OB"}(두산) 팬이 보낸 {@code "시발"}에 대한
 * 결정적 선택 결과다(QUIZ-CPF-28 — 매칭된 원문 문자열의 해시 mod 후보 수).
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceProfanityMaskingTest {

    private static final String ROOM_UID = "room-uid-1";
    private static final Long TEAM_ID = 6L;
    private static final Long SENDER_ID = 1L;

    @Mock
    private ChatroomRepository chatroomRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private RealtimeEventPublisher eventPublisher;

    @Mock
    private SseEmitterRegistry emitterRegistry;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ProfanityFilter filter = new ProfanityFilter(new ProfanityDataLoader(new ObjectMapper()));
        chatService = new ChatService(chatroomRepository, chatRepository, userAccountRepository,
                userSupportTeamRepository, eventPublisher, emitterRegistry, filter);
    }

    private void givenDoosanFanInDoosanRoom() {
        Team doosan = Team.builder().name("두산").code("OB").build();
        ReflectionTestUtils.setField(doosan, "id", TEAM_ID);
        UserAccount sender = UserAccount.builder().nickname("두산팬1").password("password1!").build();
        ReflectionTestUtils.setField(sender, "id", SENDER_ID);
        UserAccount owner = UserAccount.builder().nickname("시스템계정").password("password1!").build();
        ReflectionTestUtils.setField(owner, "id", 999L);
        Chatroom room = Chatroom.builder().team(doosan).owner(owner).name("두산 채팅방").build();
        ReflectionTestUtils.setField(room, "uid", ROOM_UID);

        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(SENDER_ID))
                .willReturn(Optional.of(UserSupportTeam.builder().userAccount(sender).team(doosan).build()));
        given(userAccountRepository.findById(SENDER_ID)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("[QUIZ-CPF-1/2/3] 두산 팬이 \"시발 오늘 왜 저럼\"을 보내면 "
            + "저장값·201 응답·SSE payload가 모두 \"두산 오늘 왜 저럼\"으로 일치한다")
    void sendMessage_realFilter_storesAndReturnsAndPublishesSameMaskedString() {
        givenDoosanFanInDoosanRoom();

        MessageResponse response = chatService.sendMessage(ROOM_UID, SENDER_ID, "시발 오늘 왜 저럼");

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(chatCaptor.capture());
        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), eventCaptor.capture());
        MessageEvent payload = (MessageEvent) eventCaptor.getValue().data();

        assertThat(chatCaptor.getValue().getContent()).isEqualTo("두산 오늘 왜 저럼");
        assertThat(response.content()).isEqualTo("두산 오늘 왜 저럼");
        assertThat(payload.content()).isEqualTo("두산 오늘 왜 저럼");
    }

    @Test
    @DisplayName("[QUIZ-CPF-6] content 전체가 금지어인 메시지도 거절되지 않고 치환된 채 저장된다")
    void sendMessage_realFilter_profanityOnlyContentIsAcceptedAndMasked() {
        givenDoosanFanInDoosanRoom();

        MessageResponse response = chatService.sendMessage(ROOM_UID, SENDER_ID, "시발");

        assertThat(response.content()).isEqualTo("두산");
        verify(chatRepository).saveAndFlush(any(Chat.class));
    }

    @Test
    @DisplayName("[QUIZ-CPF-8] 금지어가 없는 메시지는 원문과 문자 단위로 동일하게 저장된다")
    void sendMessage_realFilter_cleanContentIsStoredVerbatim() {
        givenDoosanFanInDoosanRoom();

        MessageResponse response = chatService.sendMessage(ROOM_UID, SENDER_ID, "오늘 경기 좋다!! 😀");

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(chatCaptor.capture());
        assertThat(chatCaptor.getValue().getContent()).isEqualTo("오늘 경기 좋다!! 😀");
        assertThat(response.content()).isEqualTo("오늘 경기 좋다!! 😀");
    }

    @Test
    @DisplayName("[QUIZ-CPF-9] 마스킹으로 길이가 500자를 넘어도 자르거나 거절하지 않고 그대로 저장한다"
            + "(chats.content 는 TEXT 이고 @Size(max=500) 은 원문에만 걸린다)")
    void sendMessage_realFilter_maskedContentMayExceed500Chars() {
        givenDoosanFanInDoosanRoom();
        String original = "a".repeat(400) + "ㅗ".repeat(100); // 원문 500자 = 입력 상한 통과

        MessageResponse response = chatService.sendMessage(ROOM_UID, SENDER_ID, original);

        assertThat(response.content().length()).isGreaterThan(500);
    }
}
