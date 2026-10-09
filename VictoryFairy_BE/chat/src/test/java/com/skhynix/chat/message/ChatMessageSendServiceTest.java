package com.skhynix.chat.message;

import static com.skhynix.chat.support.ChatFixtures.NOON_KST_UTC;
import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skhynix.chat.global.validation.RequestBodyValidator;
import com.skhynix.chat.message.dto.SendMessageRequest;
import com.skhynix.chat.message.dto.SendMessageResponse;
import com.skhynix.chat.message.service.ChatMessageSendService;
import com.skhynix.chat.message.service.MessageDedupStore;
import com.skhynix.chat.message.service.MessageDedupStore.Claim;
import com.skhynix.chat.message.service.MessageDedupStore.ConfirmedValue;
import com.skhynix.chat.message.service.SendRateLimiter;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.profanity.ProfanityDataLoader;
import com.skhynix.profanity.ProfanityDetector;
import jakarta.validation.Validation;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.ObjectMapper;

/**
 * 전송 서비스. 판정 순서(404 → 400 → 마스킹 → 429 → dedup → produce → 확정)가 계약이라 InOrder 로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatMessageSendServiceTest {

    private static final String GAME_ID = "20261009HTLG0";
    private static final Long SENDER_ID = 7L;
    private static final String CLIENT_MSG_ID = "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab";
    private static final String DEDUP_KEY = "chat:dedup:" + GAME_ID + ":" + CLIENT_MSG_ID;

    private static final ProfanityDetector DETECTOR = new ProfanityDetector(new ProfanityDataLoader(new ObjectMapper()));

    @Mock
    private ChatRoomGuard roomGuard;
    @Mock
    private SendRateLimiter rateLimiter;
    @Mock
    private MessageDedupStore dedupStore;
    @Mock
    private ChatKafkaProducer producer;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    private ChatMessageSendService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(DETECTOR);
        UserAccount sender = mock(UserAccount.class);
        when(sender.getNickname()).thenReturn("두산팬1");
        when(sender.getProfileImgUrl()).thenReturn("user-profile-img/7.jpg");
        given(userAccountRepository.findById(SENDER_ID)).willReturn(Optional.of(sender));
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn("OB");
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(SENDER_ID))
                .willReturn(Optional.of(support));
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID)).willReturn(new Claim.Acquired(DEDUP_KEY));
        given(producer.sendMessage(any())).willReturn(4402L);
    }

    private ChatMessageSendService serviceWith(ProfanityDetector detector) {
        RequestBodyValidator validator = new RequestBodyValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        return new ChatMessageSendService(roomGuard, validator, detector, rateLimiter, dedupStore, producer,
                userAccountRepository, userSupportTeamRepository, clockAt(NOON_KST_UTC));
    }

    private SendMessageRequest request(String content) {
        return new SendMessageRequest(content, CLIENT_MSG_ID);
    }

    private ChatMessagePayload producedPayload() {
        ArgumentCaptor<ChatMessagePayload> captor = ArgumentCaptor.forClass(ChatMessagePayload.class);
        verify(producer).sendMessage(captor.capture());
        return captor.getValue();
    }

    // ---------- 해피 패스 ----------

    @Test
    @DisplayName("[CHAT-GC-50] 유효한 전송은 produce 한 레코드 오프셋을 msgId 로 하는 {gameId, msgId, content} 를 돌려준다")
    void send_valid_returnsOffsetAsMsgIdAndMaskedContent() throws Exception {
        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("오늘 이긴다"));

        assertThat(response).isEqualTo(new SendMessageResponse(GAME_ID, 4402L, "오늘 이긴다"));
    }

    @Test
    @DisplayName("[CHAT-GC-51] 판정 순서는 방 확인 → 속도 제한 → dedup 선점 → produce → dedup 확정 이다")
    void send_valid_followsContractOrder() throws Exception {
        service.send(GAME_ID, SENDER_ID, request("안녕"));

        InOrder order = inOrder(roomGuard, rateLimiter, dedupStore, producer);
        order.verify(roomGuard).requireOrCreate(GAME_ID);
        order.verify(rateLimiter).check(SENDER_ID);
        order.verify(dedupStore).claim(GAME_ID, CLIENT_MSG_ID);
        order.verify(producer).sendMessage(any());
        order.verify(dedupStore).confirm(DEDUP_KEY, 4402L, "안녕");
    }

    @Test
    @DisplayName("[CHAT-GC-63] produce 하는 레코드는 gameId·senderId·닉네임·teamCode·프로필·마스킹 content·sentAt(+09:00) 7개 필드를 담는다")
    void send_valid_payloadHasSevenSnapshotFields() throws Exception {
        service.send(GAME_ID, SENDER_ID, request("안녕"));

        ChatMessagePayload payload = producedPayload();
        assertThat(payload.gameId()).isEqualTo(GAME_ID);
        assertThat(payload.senderId()).isEqualTo(SENDER_ID);
        assertThat(payload.senderNickname()).isEqualTo("두산팬1");
        assertThat(payload.teamCode()).isEqualTo("OB");
        assertThat(payload.profileImgUrl()).isEqualTo("user-profile-img/7.jpg");
        assertThat(payload.content()).isEqualTo("안녕");
        assertThat(payload.sentAt()).isEqualTo("2026-10-09T12:00:00.000+09:00");
        assertThat(ChatMessagePayload.class.getRecordComponents()).hasSize(7);
    }

    @Test
    @DisplayName("[CHAT-GC-63] 응원 구단이 없는 발신자의 teamCode 는 null 이다(전송은 막지 않는다)")
    void send_senderWithoutSupportTeam_teamCodeIsNull() throws Exception {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(SENDER_ID))
                .willReturn(Optional.empty());

        service.send(GAME_ID, SENDER_ID, request("안녕"));

        assertThat(producedPayload().teamCode()).isNull();
    }

    @Test
    @DisplayName("[CHAT-GC-13] 응원 구단이 없는 계정도 202 경로를 그대로 통과한다")
    void send_senderWithoutSupportTeam_isNotRejected() throws Exception {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(SENDER_ID))
                .willReturn(Optional.empty());

        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("안녕"));

        assertThat(response.msgId()).isEqualTo(4402L);
    }

    // ---------- 마스킹 ----------

    @Test
    @DisplayName("[CHAT-GC-54] 욕설은 같은 길이의 * 로 바뀌어 202 본문과 Kafka 레코드에 실린다(원문은 어디에도 없다)")
    void send_profanity_isMaskedInResponseAndRecord() throws Exception {
        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("시발 오늘"));

        assertThat(response.content()).isEqualTo("** 오늘");
        assertThat(producedPayload().content()).isEqualTo("** 오늘");
        verify(dedupStore).confirm(DEDUP_KEY, 4402L, "** 오늘");
    }

    @Test
    @DisplayName("[CHAT-GC-55] 금지어만 있는 메시지도 거절하지 않고 마스킹해 202 경로로 보낸다")
    void send_onlyProfanity_isNotRejected() throws Exception {
        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("시발"));

        assertThat(response.content()).isEqualTo("**");
        assertThat(producedPayload().content()).isEqualTo("**");
    }

    @Test
    @DisplayName("[CHAT-GC-57] 마스킹이 예외로 실패하면 예외가 그대로 나가고 속도 제한·dedup·produce 어느 것도 하지 않는다")
    void send_maskingFails_noProduceNoDedup() {
        ProfanityDetector broken = mock(ProfanityDetector.class);
        when(broken.maskWithAsterisks(any())).thenThrow(new IllegalStateException("filter down"));
        ChatMessageSendService failing = serviceWith(broken);

        assertThatThrownBy(() -> failing.send(GAME_ID, SENDER_ID, request("안녕")))
                .isInstanceOf(IllegalStateException.class);

        verify(producer, never()).sendMessage(any());
        verifyNoInteractions(dedupStore);
        verify(rateLimiter, never()).check(anyLong());
    }

    // ---------- 404 / 400 ----------

    @Test
    @DisplayName("[CHAT-GC-51] 없는 방 + 빈 content 는 404 이다(400 이 아니다) — 본문 검증보다 방 확인이 먼저다")
    void send_missingRoomAndBlankContent_is404NotValidationError() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND)).when(roomGuard).requireOrCreate(GAME_ID);

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("")), ErrorCode.CHATROOM_NOT_FOUND);

        verifyNoInteractions(rateLimiter, dedupStore, producer);
    }

    @Test
    @DisplayName("[CHAT-GC-29] 방 확인 중 Redis 장애(503)면 속도 제한·dedup·produce 모두 하지 않는다")
    void send_roomCheckRedisDown_is503() {
        doThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE)).when(roomGuard).requireOrCreate(GAME_ID);

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        verifyNoInteractions(rateLimiter, dedupStore, producer);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("null content", null, CLIENT_MSG_ID, "content"),
                Arguments.of("빈 content", "", CLIENT_MSG_ID, "content"),
                Arguments.of("공백뿐인 content", "   ", CLIENT_MSG_ID, "content"),
                Arguments.of("501자", "가".repeat(501), CLIENT_MSG_ID, "content"),
                Arguments.of("이모지 251개 = 502 code unit", "😀".repeat(251), CLIENT_MSG_ID, "content"),
                Arguments.of("clientMsgId 누락", "안녕", null, "clientMsgId"),
                Arguments.of("clientMsgId 가 abc", "안녕", "abc", "clientMsgId"),
                Arguments.of("clientMsgId 가 36자지만 UUID 형식 아님", "안녕", "z".repeat(36), "clientMsgId"),
                Arguments.of("clientMsgId 에 키 구분자 주입", "안녕", "3f9c2e10-aaaa-4bbb-8ccc-0123456789a:", "clientMsgId"));
    }

    @ParameterizedTest(name = "[CHAT-GC-52/53] {0} 이면 400(MethodArgumentNotValid)이고 속도 제한·dedup·produce 를 하지 않는다")
    @MethodSource("invalidBodies")
    void send_invalidBody_isRejectedBeforeRateLimit(String label, String content, String clientMsgId, String field) {
        assertThatThrownBy(() -> service.send(GAME_ID, SENDER_ID, new SendMessageRequest(content, clientMsgId)))
                .isInstanceOfSatisfying(MethodArgumentNotValidException.class, e ->
                        assertThat(e.getBindingResult().getFieldErrors()).extracting("field").contains(field));

        verify(roomGuard).requireOrCreate(GAME_ID);
        verifyNoInteractions(rateLimiter, dedupStore, producer);
    }

    @Test
    @DisplayName("[CHAT-GC-52] 정확히 500자는 통과하고 501자는 거부된다(경계)")
    void send_contentLengthBoundary_500passes() throws Exception {
        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("가".repeat(500)));

        assertThat(response.content()).hasSize(500);
    }

    @Test
    @DisplayName("[CHAT-GC-53] 대문자 UUID 도 36자 UUID 형식으로 인정한다")
    void send_uppercaseUuid_isAccepted() throws Exception {
        String upper = CLIENT_MSG_ID.toUpperCase();
        given(dedupStore.claim(GAME_ID, upper)).willReturn(new Claim.Acquired("k"));

        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, new SendMessageRequest("안녕", upper));

        assertThat(response.msgId()).isEqualTo(4402L);
    }

    // ---------- 429 / 409 / 재반환 ----------

    @Test
    @DisplayName("[CHAT-GC-58] 속도 제한을 넘기면 429 이고 dedup 키 선점·produce 를 하지 않는다")
    void send_rateLimited_noDedupNoProduce() {
        doThrow(new BusinessException(ErrorCode.CHAT_RATE_LIMIT_EXCEEDED)).when(rateLimiter).check(SENDER_ID);

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);

        verifyNoInteractions(dedupStore, producer);
    }

    @Test
    @DisplayName("[CHAT-GC-105] 같은 clientMsgId 가 PENDING 이면 409 CHAT_MESSAGE_IN_FLIGHT 이고 produce 하지 않는다")
    void send_inFlight_is409NoProduce() {
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID)).willReturn(new Claim.InFlight());

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.CHAT_MESSAGE_IN_FLIGHT);

        verify(producer, never()).sendMessage(any());
        verify(dedupStore, never()).confirm(anyString(), anyLong(), anyString());
        verify(dedupStore, never()).release(anyString());
    }

    @Test
    @DisplayName("[CHAT-GC-106] 확정된 clientMsgId 재요청은 저장된 {msgId, content} 로 같은 202 본문을 재반환하고 produce 하지 않는다")
    void send_replay_returnsStoredBodyWithoutProduce() throws Exception {
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID)).willReturn(new Claim.Replay(new ConfirmedValue(1234L, "오늘 이긴다")));

        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("다른 문장으로 재전송"));

        assertThat(response).isEqualTo(new SendMessageResponse(GAME_ID, 1234L, "오늘 이긴다"));
        verify(producer, never()).sendMessage(any());
    }

    @Test
    @DisplayName("[CHAT-GC-61] dedup 재반환·409 요청도 속도 제한을 거친다(dedup 보다 속도 제한이 먼저)")
    void send_replayAndInFlight_areCountedByRateLimiter() throws Exception {
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID))
                .willReturn(new Claim.Replay(new ConfirmedValue(1L, "x")))
                .willReturn(new Claim.InFlight());

        service.send(GAME_ID, SENDER_ID, request("a"));
        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("a")), ErrorCode.CHAT_MESSAGE_IN_FLIGHT);

        verify(rateLimiter, org.mockito.Mockito.times(2)).check(SENDER_ID);
    }

    // ---------- 503 / fail-open ----------

    @Test
    @DisplayName("[CHAT-GC-62] Kafka produce 실패는 503 CHAT_BROKER_UNAVAILABLE 이고 방금 선점한 dedup 키를 해제하며 확정하지 않는다")
    void send_kafkaFailure_is503AndReleasesDedupKey() {
        given(producer.sendMessage(any())).willThrow(new ChatPublishException("timeout", new RuntimeException()));

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        verify(dedupStore).release(DEDUP_KEY);
        verify(dedupStore, never()).confirm(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[CHAT-GC-62] produce 이전 단계(발신자 조회 등)에서 예기치 않은 예외가 나도 선점한 dedup 키를 남기지 않는다")
    void send_unexpectedFailureBeforeProduce_releasesDedupKey() {
        given(userAccountRepository.findById(SENDER_ID)).willReturn(Optional.empty());

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.UNAUTHENTICATED);

        verify(dedupStore).release(DEDUP_KEY);
        verify(producer, never()).sendMessage(any());
    }

    @Test
    @DisplayName("[CHAT-GC-65] dedup 가 Unavailable(Redis 실패) 이면 멱등 보장 없이 produce 하고 202 를 준다(확정·해제는 시도하지 않는다)")
    void send_dedupUnavailable_failsOpenAndProduces() throws Exception {
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID)).willReturn(new Claim.Unavailable());

        SendMessageResponse response = service.send(GAME_ID, SENDER_ID, request("안녕"));

        assertThat(response.msgId()).isEqualTo(4402L);
        verify(dedupStore, never()).confirm(anyString(), anyLong(), anyString());
        verify(dedupStore, never()).release(anyString());
    }

    @Test
    @DisplayName("[CHAT-GC-65] dedup Unavailable 상태에서 Kafka 가 실패해도 해제할 키가 없으므로 release 를 부르지 않고 503 이다")
    void send_dedupUnavailableAndKafkaDown_is503WithoutRelease() {
        given(dedupStore.claim(GAME_ID, CLIENT_MSG_ID)).willReturn(new Claim.Unavailable());
        given(producer.sendMessage(any())).willThrow(new ChatPublishException("down", new RuntimeException()));

        assertBusiness(() -> service.send(GAME_ID, SENDER_ID, request("안녕")), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        verify(dedupStore, never()).release(any());
    }

    @Test
    @DisplayName("[CHAT-GC-14] 202 본문에는 계정 내부 id 나 닉네임이 없다 — gameId·msgId·content 3필드뿐이다")
    void send_responseHasOnlyThreeFields() {
        assertThat(SendMessageResponse.class.getRecordComponents()).extracting("name")
                .containsExactly("gameId", "msgId", "content");
    }

}
