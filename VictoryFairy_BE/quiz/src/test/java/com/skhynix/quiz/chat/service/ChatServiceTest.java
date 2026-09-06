package com.skhynix.quiz.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
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
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.chat.profanity.ProfanityFilter;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import com.skhynix.quiz.realtime.SubscriptionCloseCommand;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ChatService}를 협력 객체(리포지토리·publisher·emitter 레지스트리) 전부를 Mockito로 대체해
 * 단위로 검증한다. DB·Spring 컨텍스트 없음.
 *
 * <p>리포지토리 쿼리 자체(예: {@code findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc}의
 * 실제 WHERE/ORDER BY 동작)는 여기서 목으로 대체되므로 검증되지 않는다 — "서비스가 리포지토리에 올바른
 * 인자로 위임하고, 리포지토리가 돌려준 결과를 가공 없이 그대로 반환한다"는 계약만 검증한다.
 *
 * <p><b>구단 가드 픽스처</b>: 방 단위 경로는 전부 {@code findAccessibleRoom}을 거치므로,
 * {@code userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull}이 방의 구단과 일치하는 값을
 * 돌려주지 않으면 403/400으로 먼저 끝난다. {@link #givenSupportTeam}/{@link #givenNoSupportTeam}으로
 * 그 스텁을 깔고, 일치시키지 않는 테스트는 그 자체가 가드 검증이다(QUIZ-CTAC-6/9~13/15 등).
 * {@code unsubscribe()}만 예외다 — 이 경로는 구단 조회 자체를 하지 않는다(QUIZ-CTAC-21/22).
 *
 * <p><b>전송 경로만 구단 조회 메서드가 다르다</b>: {@code sendMessage()}는 치환어 결정에 {@code teams.code}가
 * 필요해 {@code findWithTeamByUserAccount_IdAndOpposeIsNull}(@EntityGraph 판)을 부른다. 나머지 경로는
 * id만 쓰므로 {@code findByUserAccount_IdAndOpposeIsNull} 그대로다 — 그래서 픽스처도
 * {@link #givenSendSupportTeam}/{@link #givenNoSendSupportTeam}으로 갈라 둔다. 여기서 잘못된 쪽을 스텁하면
 * 컴파일은 되지만 런타임에 400 SUPPORT_TEAM_REQUIRED로 끝난다.
 *
 * <p><b>마스킹은 목이다</b>: 이 클래스는 "필터 결과가 저장·응답·SSE 세 곳에 같은 문자열로 흐르는가"만 본다.
 * 필터의 판정 자체는 {@code ProfanityFilterTest}가, 실제 데이터를 물린 종단 결과는
 * {@link ChatServiceProfanityMaskingTest}가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String ROOM_UID = "room-uid-1";
    private static final String OTHER_ROOM_UID = "other-room-uid";
    private static final Long SUPPORT_TEAM_ID = 6L; // 두산(예시 구단)
    private static final Long OTHER_TEAM_ID = 3L;   // LG(예시 구단)

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

    @Mock
    private ProfanityFilter profanityFilter;

    @InjectMocks
    private ChatService chatService;

    private Team team() {
        return team(SUPPORT_TEAM_ID, "두산", "OB");
    }

    private Team team(Long id, String name) {
        return team(id, name, null);
    }

    private Team team(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private Chatroom activeRoom(String uid) {
        return activeRoom(uid, team());
    }

    private Chatroom activeRoom(String uid, Team team) {
        UserAccount owner = userAccountWithId(999L, "시스템계정");
        Chatroom room = Chatroom.builder().team(team).owner(owner).name(team.getName() + " 채팅방").build();
        ReflectionTestUtils.setField(room, "uid", uid);
        return room;
    }

    private UserAccount userAccountWithId(Long id, String nickname) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("password1!").build();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    /** profileImgUrl까지 지정하는 오버로드. 미지정 시(위 2-arg)는 기본값 null(프로필 없음)이다. */
    private UserAccount userAccountWithId(Long id, String nickname, String profileImgUrl) {
        UserAccount account = userAccountWithId(id, nickname);
        account.changeProfileImgUrl(profileImgUrl);
        return account;
    }

    private Chat chatOf(Chatroom room, UserAccount author, String content) {
        return Chat.builder().chatroom(room).userAccount(author).content(content).build();
    }

    /** 지정 계정의 현재 응원 구단을 {@code team}으로 스텁한다. */
    private void givenSupportTeam(Long userAccountId, Team team) {
        UserSupportTeam support = UserSupportTeam.builder()
                .userAccount(userAccountWithId(userAccountId, "무관"))
                .team(team)
                .build();
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId))
                .willReturn(Optional.of(support));
    }

    /** 지정 계정에 응원 구단이 없는 상태(QUIZ-CTAC-3/4/15)를 스텁한다. */
    private void givenNoSupportTeam(Long userAccountId) {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId))
                .willReturn(Optional.empty());
    }

    /** 전송 경로 전용(구단 엔티티까지 함께 가져오는 @EntityGraph 판) 응원 구단 스텁. */
    private void givenSendSupportTeam(Long userAccountId, Team team) {
        UserSupportTeam support = UserSupportTeam.builder()
                .userAccount(userAccountWithId(userAccountId, "무관"))
                .team(team)
                .build();
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId))
                .willReturn(Optional.of(support));
    }

    /** 전송 경로 전용 "응원 구단 없음" 스텁. */
    private void givenNoSendSupportTeam(Long userAccountId) {
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId))
                .willReturn(Optional.empty());
    }

    /** 마스킹이 원문을 그대로 돌려주는 상태(금지어 없는 문장). 전송 계약 자체를 보는 테스트용. */
    private void givenMaskUnchanged() {
        given(profanityFilter.mask(anyString(), anyString()))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- getRooms ----------

    @Test
    @DisplayName("[QUIZ-CTAC-2] teamId를 생략하면 요청자의 현재 응원 구단으로 간주해 그 구단 방 목록을 반환한다")
    void getRooms_teamIdOmitted_fallsBackToSupportTeam() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(SUPPORT_TEAM_ID)).willReturn(List.of(room));

        List<RoomResponse> result = chatService.getRooms(null, 1L);

        assertThat(result).extracting(RoomResponse::roomUid).containsExactly(ROOM_UID);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-5] teamId가 응원 구단과 같으면 그 구단 방만 200으로 반환한다(생략했을 때와 동일 결과)")
    void getRooms_teamIdMatchesSupportTeam_returnsThatTeamRoomsOnly() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(SUPPORT_TEAM_ID)).willReturn(List.of(room));

        List<RoomResponse> result = chatService.getRooms(SUPPORT_TEAM_ID, 1L);

        assertThat(result).extracting(RoomResponse::roomUid).containsExactly(ROOM_UID);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-3] 응원 구단이 없고 teamId도 없으면 400 SUPPORT_TEAM_REQUIRED를 던진다")
    void getRooms_noSupportTeamAndNoTeamId_throwsSupportTeamRequired() {
        givenNoSupportTeam(1L);

        assertThatThrownBy(() -> chatService.getRooms(null, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
        verify(chatroomRepository, never()).findAllByTeam_IdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-4] 응원 구단이 없으면 teamId가 전달돼도 400 SUPPORT_TEAM_REQUIRED를 던진다")
    void getRooms_noSupportTeamWithTeamIdProvided_stillThrowsSupportTeamRequired() {
        givenNoSupportTeam(1L);

        assertThatThrownBy(() -> chatService.getRooms(OTHER_TEAM_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-6] teamId가 응원 구단과 다르면 403 CHATROOM_TEAM_MISMATCH를 던지고 방 목록을 조회하지 않는다")
    void getRooms_teamIdMismatchesSupportTeam_throwsChatroomTeamMismatch() {
        givenSupportTeam(1L, team());

        assertThatThrownBy(() -> chatService.getRooms(OTHER_TEAM_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
        verify(chatroomRepository, never()).findAllByTeam_IdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-7] teamId가 존재하지 않는 구단 id여도 구단 존재를 별도 조회하지 않고 QUIZ-CTAC-6과 동일한 403을 던진다")
    void getRooms_teamIdIsNonexistentTeam_throwsSameChatroomTeamMismatch() {
        givenSupportTeam(1L, team());

        assertThatThrownBy(() -> chatService.getRooms(999_999L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-1] 판정 기준 구단은 매 호출마다 새로 조회한다(캐시 없음) — "
            + "응원 구단이 바뀌면 다음 호출부터 새 구단 기준으로 필터링된다")
    void getRooms_supportTeamChangesBetweenCalls_reflectsLatestTeamEachTime() {
        Team kia = team(SUPPORT_TEAM_ID, "KIA");
        Team lg = team(OTHER_TEAM_ID, "LG");
        Chatroom kiaRoom = activeRoom(ROOM_UID, kia);
        Chatroom lgRoom = activeRoom(OTHER_ROOM_UID, lg);
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(1L))
                .willReturn(Optional.of(UserSupportTeam.builder()
                        .userAccount(userAccountWithId(1L, "무관")).team(kia).build()))
                .willReturn(Optional.of(UserSupportTeam.builder()
                        .userAccount(userAccountWithId(1L, "무관")).team(lg).build()));
        given(chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(SUPPORT_TEAM_ID)).willReturn(List.of(kiaRoom));
        given(chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(OTHER_TEAM_ID)).willReturn(List.of(lgRoom));

        List<RoomResponse> first = chatService.getRooms(null, 1L);
        List<RoomResponse> second = chatService.getRooms(null, 1L);

        assertThat(first).extracting(RoomResponse::roomUid).containsExactly(ROOM_UID);
        assertThat(second).extracting(RoomResponse::roomUid).containsExactly(OTHER_ROOM_UID);
    }

    // ---------- getRoom ----------

    @Test
    @DisplayName("getRoom()은 존재하는 방이면 RoomResponse를 반환한다")
    void getRoom_activeRoom_returnsRoomResponse() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        RoomResponse result = chatService.getRoom(ROOM_UID, 1L);

        assertThat(result.roomUid()).isEqualTo(ROOM_UID);
    }

    @Test
    @DisplayName("getRoom()은 없거나 삭제된 방이면 BusinessException(CHATROOM_NOT_FOUND)을 던진다")
    void getRoom_missingOrDeletedRoom_throwsChatroomNotFound() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getRoom("nope", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-9] getRoom()은 방의 구단이 응원 구단과 다르면 403 CHATROOM_TEAM_MISMATCH를 던진다")
    void getRoom_teamMismatch_throwsChatroomTeamMismatch() {
        Chatroom room = activeRoom(ROOM_UID, team(OTHER_TEAM_ID, "LG"));
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> chatService.getRoom(ROOM_UID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-14] 존재하지 않는 방은 응원 구단 조회 자체를 하지 않고 404를 던진다(404가 구단 판정보다 먼저)")
    void getRoom_missingRoom_neverQueriesSupportTeam() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getRoom("nope", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndOpposeIsNull(any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15] getRoom()은 응원 구단이 없으면 활성 방이 실재해도 403이 아니라 400 SUPPORT_TEAM_REQUIRED를 던진다")
    void getRoom_noSupportTeam_throwsSupportTeamRequiredEvenWhenRoomExists() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        givenNoSupportTeam(1L);

        assertThatThrownBy(() -> chatService.getRoom(ROOM_UID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
    }

    // ---------- subscribe ----------

    @Test
    @DisplayName("subscribe()는 방이 존재하고 응원 구단이 일치하면 emitterRegistry.register()에 위임해 SseEmitter를 반환한다")
    void subscribe_activeRoom_delegatesToEmitterRegistry() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        SseEmitter emitter = new SseEmitter();
        given(emitterRegistry.register(ROOM_UID, 1L)).willReturn(emitter);

        SseEmitter result = chatService.subscribe(ROOM_UID, 1L);

        assertThat(result).isSameAs(emitter);
        verify(emitterRegistry).register(ROOM_UID, 1L);
    }

    @Test
    @DisplayName("subscribe()는 없거나 삭제된 방이면 404를 던지고 emitterRegistry.register()를 호출하지 않는다")
    void subscribe_missingRoom_throwsAndNeverRegisters() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.subscribe("nope", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(emitterRegistry, never()).register(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-10] subscribe()는 방의 구단이 응원 구단과 다르면 403을 던지고 "
            + "emitterRegistry.register()를 호출하지 않는다(스트림을 열지 않고 레지스트리에도 등록하지 않음)")
    void subscribe_teamMismatch_throwsAndNeverRegisters() {
        Chatroom room = activeRoom(ROOM_UID, team(OTHER_TEAM_ID, "LG"));
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> chatService.subscribe(ROOM_UID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
        verify(emitterRegistry, never()).register(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15] subscribe()는 응원 구단이 없으면 400을 던지고 emitterRegistry.register()를 호출하지 않는다")
    void subscribe_noSupportTeam_throwsAndNeverRegisters() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        givenNoSupportTeam(1L);

        assertThatThrownBy(() -> chatService.subscribe(ROOM_UID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
        verify(emitterRegistry, never()).register(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-24/29] subscribe()는 등록 후 같은 사용자를 대상으로 한 "
            + "축출(evict, allRooms=true) 종료 명령을 발행한다(다중 파드 전파의 발신측)")
    void subscribe_success_publishesEvictCloseCommandTargetingOwnUser() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(emitterRegistry.register(ROOM_UID, 1L)).willReturn(new SseEmitter());
        given(emitterRegistry.instanceId()).willReturn("instance-A");

        chatService.subscribe(ROOM_UID, 1L);

        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), captor.capture());
        RealtimeEvent event = captor.getValue();
        assertThat(event.name()).isEqualTo(SubscriptionCloseCommand.EVENT_NAME);
        assertThat(event.data()).isInstanceOf(SubscriptionCloseCommand.class);
        SubscriptionCloseCommand command = (SubscriptionCloseCommand) event.data();
        assertThat(command.targetUserAccountId()).isEqualTo(1L);
        assertThat(command.originInstanceId()).isEqualTo("instance-A");
        assertThat(command.allRooms()).isTrue();
    }

    // ---------- unsubscribe ----------

    @Test
    @DisplayName("[QUIZ-CTAC-19] unsubscribe()는 emitterRegistry.closeSubscriptions()에 위임해 그 방 구독을 종료한다")
    void unsubscribe_delegatesToEmitterRegistryCloseSubscriptions() {
        chatService.unsubscribe(ROOM_UID, 1L);

        verify(emitterRegistry).closeSubscriptions(ROOM_UID, 1L);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-19/28] unsubscribe()는 그 방에 한정된(allRooms=false) leave 종료 명령을 발행한다"
            + "(다중 파드 전파의 발신측)")
    void unsubscribe_publishesLeaveCloseCommandScopedToRoom() {
        given(emitterRegistry.instanceId()).willReturn("instance-A");

        chatService.unsubscribe(ROOM_UID, 1L);

        ArgumentCaptor<RealtimeEvent> captor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), captor.capture());
        RealtimeEvent event = captor.getValue();
        assertThat(event.name()).isEqualTo(SubscriptionCloseCommand.EVENT_NAME);
        SubscriptionCloseCommand command = (SubscriptionCloseCommand) event.data();
        assertThat(command.targetUserAccountId()).isEqualTo(1L);
        assertThat(command.originInstanceId()).isEqualTo("instance-A");
        assertThat(command.allRooms()).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CTAC-20] 끊을 구독이 없어도 unsubscribe()는 예외를 던지지 않는다(멱등)")
    void unsubscribe_noExistingSubscription_doesNotThrow() {
        assertThatCode(() -> chatService.unsubscribe(ROOM_UID, 1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[QUIZ-CTAC-20] 같은 해제 요청을 연속 2회 보내도 둘 다 예외 없이 처리되고 "
            + "emitterRegistry.closeSubscriptions()가 매번 호출된다")
    void unsubscribe_calledTwiceConsecutively_bothSucceed() {
        chatService.unsubscribe(ROOM_UID, 1L);
        chatService.unsubscribe(ROOM_UID, 1L);

        verify(emitterRegistry, times(2)).closeSubscriptions(ROOM_UID, 1L);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-21/22/26] unsubscribe()는 구단 일치·응원 구단 존재·방 존재 여부를 전혀 조회하지 않는다"
            + "(구단을 바꾼 사용자·응원 구단 없는 사용자도 정리할 수 있어야 하고, "
            + "chatroomRepository를 건드리지 않으므로 participants도 건드릴 수단 자체가 없다)")
    void unsubscribe_neverChecksTeamOrRoomExistence() {
        assertThatCode(() -> chatService.unsubscribe(ROOM_UID, 1L)).doesNotThrowAnyException();

        verify(chatroomRepository, never()).findByUidAndDeletedAtIsNull(anyString());
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndOpposeIsNull(any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-23] 존재하지 않거나 삭제된 roomUid로 구독 해제를 요청해도 예외 없이 처리된다(404 아님)")
    void unsubscribe_nonexistentRoomUid_doesNotThrow() {
        assertThatCode(() -> chatService.unsubscribe("does-not-exist", 1L)).doesNotThrowAnyException();

        verify(emitterRegistry).closeSubscriptions("does-not-exist", 1L);
        verify(chatroomRepository, never()).findByUidAndDeletedAtIsNull(anyString());
    }

    // ---------- sendMessage ----------

    @Test
    @DisplayName("[AC-CHAT-10-1] sendMessage()는 방·발신자가 유효하면 blind=false로 저장하고 저장된 메시지를 반환한다")
    void sendMessage_validRoomAndSender_savesWithBlindFalse() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1", "user-profile-img/1.jpg");
        givenSendSupportTeam(1L, team());
        givenMaskUnchanged();
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "안녕");

        ArgumentCaptor<Chat> captor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(captor.capture());
        Chat saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("안녕");
        assertThat(saved.isBlind()).isFalse();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getUserAccount()).isSameAs(sender);
        assertThat(response.content()).isEqualTo("안녕");
        assertThat(response.senderNickname()).isEqualTo("두산팬1");
        assertThat(response.profileImgUrl()).isEqualTo("user-profile-img/1.jpg");
    }

    @Test
    @DisplayName("[신규] sendMessage()는 발신자에게 프로필 이미지가 없으면(탈퇴자 이관용 더미 계정 등) "
            + "profileImgUrl을 null로 응답한다 — MessageResponse.from()이 접근자를 그대로 읽을 뿐 "
            + "별도 분기가 없어 자연히 null이 된다")
    void sendMessage_senderWithoutProfileImg_responseProfileImgUrlIsNull() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "(알수없음)"); // profileImgUrl 미설정 = 기본 null
        givenSendSupportTeam(1L, team());
        givenMaskUnchanged();
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "안녕");

        assertThat(response.profileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[AC-CHAT-11/15-1] sendMessage()는 저장 후 발신자를 제외 대상으로 지정하고 "
            + "{id, content, senderNickname, profileImgUrl, createdAt, roomUid} 6필드로 구성된 "
            + "MessageEvent를 payload로 publish한다")
    void sendMessage_publishesRealtimeEventExcludingSenderWithMessageEventPayload() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1", "user-profile-img/1.jpg");
        givenSendSupportTeam(1L, team());
        givenMaskUnchanged();
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        // saveAndFlush 목이 실제 JPA persist처럼 @CreationTimestamp(createdAt)를 채워주지 않으므로,
        // MessageEvent.createdAt() 검증을 의미 있게 만들기 위해 여기서 직접 채워 넣는다.
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> {
            Chat chat = invocation.getArgument(0);
            ReflectionTestUtils.setField(chat, "createdAt", LocalDateTime.of(2026, 7, 20, 9, 0));
            // 저장 시 채워지는 PK도 목이 대신 채운다(payload.id() 검증을 의미 있게 만들기 위함).
            ReflectionTestUtils.setField(chat, "id", 7L);
            return chat;
        });

        chatService.sendMessage(ROOM_UID, 1L, "안녕");

        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), eventCaptor.capture());
        RealtimeEvent event = eventCaptor.getValue();
        assertThat(event.name()).isEqualTo("message");
        assertThat(event.excludeUserAccountId()).isEqualTo(1L);
        assertThat(event.data()).isInstanceOf(MessageEvent.class);
        MessageEvent payload = (MessageEvent) event.data();
        assertThat(payload.content()).isEqualTo("안녕");
        assertThat(payload.senderNickname()).isEqualTo("두산팬1");
        assertThat(payload.profileImgUrl()).isEqualTo("user-profile-img/1.jpg");
        assertThat(payload.roomUid()).isEqualTo(ROOM_UID);
        assertThat(payload.createdAt()).isNotNull();
        // id 는 저장된 Chat 의 PK 그대로 — 클라이언트가 히스토리 중복 제거·신고에 쓴다(QUIZ-CHAT-15 개정).
        assertThat(payload.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("[AC-CHAT-17] 트랜잭션 동기화가 활성이면 발행을 커밋 이후로 미룬다 — 커밋 전에는 "
            + "publish 하지 않는다(커밋 실패 시 DB에 없는 유령 메시지가 전달되는 것을 막는다)")
    void sendMessage_defersPublishUntilAfterCommit() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        givenMaskUnchanged();
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));
        // 실제 @Transactional 대신 동기화만 활성화해 "트랜잭션 안에서 호출된 상황"을 만든다.
        TransactionSynchronizationManager.initSynchronization();
        try {
            chatService.sendMessage(ROOM_UID, 1L, "안녕");

            // 커밋 전: 아직 아무것도 나가지 않았다.
            verify(eventPublisher, never()).publish(anyString(), any());

            // 커밋 시점에 등록된 콜백이 실행되면 그때 발행된다.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(eventPublisher).publish(eq(ROOM_UID), any(RealtimeEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("[AC-CHAT-14-1][QUIZ-CPF-7] sendMessage()는 존재하지 않는 방이면 404를 던지고 "
            + "마스킹·저장·발행 어느 것도 하지 않는다(마스킹은 검증 4단계를 통과한 뒤 저장 직전에 온다)")
    void sendMessage_roomNotFound_throwsWithoutSavingOrPublishing() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage("nope", 1L, "시발"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(profanityFilter, never()).mask(anyString(), any());
        verify(chatRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("[AC-CHAT-17] pub/sub 발행이 실패해도 저장은 유지되고 sendMessage()는 예외 없이 응답을 반환한다(fire-and-forget)")
    void sendMessage_publishFails_stillReturnsResponseWithoutPropagatingException() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        givenMaskUnchanged();
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));
        doThrowOnPublish();

        // sendMessage()가 예외를 전파했다면 아래 호출 자체가 실패해 테스트가 깨진다(그것으로 이미 증명됨).
        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "안녕");

        assertThat(response.content()).isEqualTo("안녕");
        verify(chatRepository).saveAndFlush(any(Chat.class));
    }

    private void doThrowOnPublish() {
        org.mockito.Mockito.doThrow(new RuntimeException("pub/sub down"))
                .when(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("경계(요구사항 미기재, 방어 코드): 발신자 계정을 찾을 수 없으면 UNAUTHENTICATED를 던지고 저장하지 않는다")
    void sendMessage_senderNotFound_throwsUnauthenticated() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSendSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage(ROOM_UID, 1L, "안녕"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        verify(chatRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-11][QUIZ-CPF-7] sendMessage()는 방의 구단이 응원 구단과 다르면 403을 던지고 "
            + "마스킹·저장·발행하지 않는다")
    void sendMessage_teamMismatch_throwsAndNeverSavesOrPublishes() {
        Chatroom room = activeRoom(ROOM_UID, team(OTHER_TEAM_ID, "LG", "LG"));
        givenSendSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> chatService.sendMessage(ROOM_UID, 1L, "시발"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
        verify(profanityFilter, never()).mask(anyString(), any());
        verify(chatRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15][QUIZ-CPF-7] sendMessage()는 응원 구단이 없으면 400을 던지고 마스킹·저장하지 않는다")
    void sendMessage_noSupportTeam_throwsAndNeverSaves() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        givenNoSendSupportTeam(1L);

        assertThatThrownBy(() -> chatService.sendMessage(ROOM_UID, 1L, "시발"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
        verify(profanityFilter, never()).mask(anyString(), any());
        verify(chatRepository, never()).saveAndFlush(any());
    }

    // ---------- sendMessage: 욕설 마스킹 (QUIZ-CPF-1~7, 36) ----------

    @Test
    @DisplayName("[QUIZ-CPF-1/2/3] sendMessage()는 저장·201 응답·SSE payload 세 곳 모두에 "
            + "마스킹된 같은 문자열을 싣는다(원문은 어디에도 남지 않는다)")
    void sendMessage_masksContentAndUsesSameStringEverywhere() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1", "user-profile-img/1.jpg");
        givenSendSupportTeam(1L, team());
        given(profanityFilter.mask("시발 오늘 왜 저럼", "OB")).willReturn("망곰 오늘 왜 저럼");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "시발 오늘 왜 저럼");

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(chatCaptor.capture());
        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), eventCaptor.capture());
        MessageEvent payload = (MessageEvent) eventCaptor.getValue().data();

        assertThat(chatCaptor.getValue().getContent()).isEqualTo("망곰 오늘 왜 저럼");
        assertThat(response.content()).isEqualTo("망곰 오늘 왜 저럼");
        assertThat(payload.content()).isEqualTo("망곰 오늘 왜 저럼");
    }

    @Test
    @DisplayName("[QUIZ-CPF-4] sendMessage()는 마스킹 이전의 원문을 저장하지 않는다 — "
            + "엔티티에 실리는 값은 필터 결과 하나뿐이다")
    void sendMessage_neverStoresOriginalContent() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        given(profanityFilter.mask("시발", "OB")).willReturn("망곰");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "시발");

        ArgumentCaptor<Chat> chatCaptor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(chatCaptor.capture());
        assertThat(chatCaptor.getValue().getContent()).doesNotContain("시발");
        assertThat(response.content()).doesNotContain("시발");
    }

    @Test
    @DisplayName("[QUIZ-CPF-6] content 전체가 금지어여도 거절하지 않는다 — 예외 없이 1행 저장하고 응답을 돌려준다")
    void sendMessage_contentEntirelyProfane_isStillAccepted() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        given(profanityFilter.mask("시발", "OB")).willReturn("망곰");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "시발");

        assertThat(response.content()).isEqualTo("망곰");
        verify(chatRepository, times(1)).saveAndFlush(any(Chat.class));
    }

    @Test
    @DisplayName("[QUIZ-CPF-26] 치환어 결정에 넘기는 구단은 방의 구단이 아니라 발신자의 현재 응원 구단 code 다"
            + "(전송 경로에서는 둘이 늘 같지만, 넘기는 값의 출처는 응원 구단 쪽이다)")
    void sendMessage_passesSenderSupportTeamCodeToFilter() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        given(profanityFilter.mask(anyString(), anyString())).willReturn("망곰");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        chatService.sendMessage(ROOM_UID, 1L, "시발");

        verify(profanityFilter).mask("시발", "OB");
    }

    @Test
    @DisplayName("[QUIZ-CPF-36] 마스킹이 예외로 실패하면 저장·발행 없이 전송을 실패시킨다 — "
            + "원문을 그대로 저장하는 fallback 은 없다(필터가 꺼진 줄 모른 채 욕설이 저장되는 것을 막는다)")
    void sendMessage_maskingThrows_failsWithoutSaving() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        givenSendSupportTeam(1L, team());
        given(profanityFilter.mask("시발", "OB")).willThrow(new IllegalStateException("필터 데이터 손상"));
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));

        assertThatThrownBy(() -> chatService.sendMessage(ROOM_UID, 1L, "시발"))
                .isInstanceOf(IllegalStateException.class);
        verify(chatRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    // ---------- getHistory ----------

    @Test
    @DisplayName("[AC-CHAT-18] getHistory()는 page·30건 Pageable로 리포지토리에 위임하고 결과를 PageResponse로 감싼다")
    void getHistory_delegatesToRepositoryWithPageableAndWrapsResult() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        UserAccount author = userAccountWithId(1L, "닉네임", "user-profile-img/history.jpg");
        Chat chat = chatOf(room, author, "내용");
        Page<Chat> repoPage = new PageImpl<>(List.of(chat), PageRequest.of(0, 30), 1);
        given(chatRepository.findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), any(Pageable.class))).willReturn(repoPage);

        PageResponse<MessageResponse> result = chatService.getHistory(ROOM_UID, 0, 1L);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatRepository).findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(30);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).content()).isEqualTo("내용");
        assertThat(result.content().get(0).profileImgUrl()).isEqualTo("user-profile-img/history.jpg");
    }

    @Test
    @DisplayName("[신규] getHistory()는 항목마다 프로필 이미지 유무를 그대로 실어 보낸다 — "
            + "프로필이 있는 발신자와 없는 발신자(탈퇴자 이관 더미 계정 등)가 한 페이지에 섞여도 "
            + "값이 있는 쪽은 그대로, 없는 쪽은 null로 유지된다")
    void getHistory_mixedProfileImgUrl_preservesValueAndNullPerMessage() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        UserAccount withProfile = userAccountWithId(2L, "닉1", "user-profile-img/2.jpg");
        UserAccount withoutProfile = userAccountWithId(3L, "(알수없음)"); // 프로필 없음(기본 null)
        Chat chatWithProfile = chatOf(room, withProfile, "내용1");
        Chat chatWithoutProfile = chatOf(room, withoutProfile, "내용2");
        Page<Chat> repoPage = new PageImpl<>(List.of(chatWithProfile, chatWithoutProfile), PageRequest.of(0, 30), 2);
        given(chatRepository.findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), any(Pageable.class))).willReturn(repoPage);

        PageResponse<MessageResponse> result = chatService.getHistory(ROOM_UID, 0, 1L);

        assertThat(result.content().get(0).profileImgUrl()).isEqualTo("user-profile-img/2.jpg");
        assertThat(result.content().get(1).profileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-CPF-33] getHistory()는 저장값을 그대로 반환하고 마스킹을 다시 적용하지 않는다"
            + "(필터 도입 이전 메시지도 소급 치환되지 않는다)")
    void getHistory_neverReappliesMasking() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        UserAccount author = userAccountWithId(2L, "닉네임");
        // 필터 도입 이전에 저장돼 원문 욕설이 그대로 남아 있는 과거 메시지
        Chat legacy = chatOf(room, author, "시발 예전 메시지");
        given(chatRepository.findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(legacy), PageRequest.of(0, 30), 1));

        PageResponse<MessageResponse> result = chatService.getHistory(ROOM_UID, 0, 1L);

        assertThat(result.content().get(0).content()).isEqualTo("시발 예전 메시지");
        verify(profanityFilter, never()).mask(anyString(), any());
    }

    @Test
    @DisplayName("[AC-CHAT-18-4] getHistory()는 없거나 삭제된 방이면 404를 던진다")
    void getHistory_missingRoom_throwsChatroomNotFound() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getHistory("nope", 0, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-12] getHistory()는 방의 구단이 응원 구단과 다르면 403을 던진다")
    void getHistory_teamMismatch_throwsChatroomTeamMismatch() {
        Chatroom room = activeRoom(ROOM_UID, team(OTHER_TEAM_ID, "LG"));
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> chatService.getHistory(ROOM_UID, 0, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
        verify(chatRepository, never())
                .findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15] getHistory()는 응원 구단이 없으면 400을 던진다")
    void getHistory_noSupportTeam_throwsSupportTeamRequired() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        givenNoSupportTeam(1L);

        assertThatThrownBy(() -> chatService.getHistory(ROOM_UID, 0, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
    }

    // ---------- reportMessage ----------

    @Test
    @DisplayName("[AC-CHAT-20-1] reportMessage()는 타인의 정상 메시지를 즉시 blind=true로 전환한다")
    void reportMessage_normalMessage_setsBlindTrue() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        givenSupportTeam(2L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        chatService.reportMessage(ROOM_UID, 42L, 2L); // 신고자(2L) != 작성자(1L)

        assertThat(chat.isBlind()).isTrue();
    }

    @Test
    @DisplayName("[AC-CHAT-27-1] reportMessage()는 자기 메시지를 신고하면 403을 던지고 blind 상태를 바꾸지 않는다")
    void reportMessage_selfReport_throwsAndKeepsBlindFalse() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        givenSupportTeam(1L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 1L)) // 신고자 == 작성자
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SELF_REPORT_NOT_ALLOWED);
        assertThat(chat.isBlind()).isFalse();
    }

    @Test
    @DisplayName("[AC-CHAT-28-1] reportMessage()는 이미 blind인 메시지를 재신고해도 예외 없이 멱등하게 유지한다")
    void reportMessage_alreadyBlindMessage_isIdempotent() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        chat.blind(); // 이미 신고돼 blind 상태
        givenSupportTeam(2L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatCode(() -> chatService.reportMessage(ROOM_UID, 42L, 2L)).doesNotThrowAnyException();
        assertThat(chat.isBlind()).isTrue();
    }

    @Test
    @DisplayName("[AC-CHAT-29-1] reportMessage()는 이미 소프트삭제된 메시지를 신고하면 404를 던진다")
    void reportMessage_deletedMessage_throwsChatMessageNotFound() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        chat.delete(LocalDateTime.now());
        givenSupportTeam(2L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("[AC-CHAT-20-2] reportMessage()는 방 안에서 messageId를 찾지 못하면 404를 던진다")
    void reportMessage_messageNotFoundInRoom_throwsChatMessageNotFound() {
        Chatroom room = activeRoom(ROOM_UID);
        givenSupportTeam(2L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(999L, room)).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 999L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("reportMessage()는 방이 없으면 404를 던지고 메시지 조회 자체를 시도하지 않는다")
    void reportMessage_roomNotFound_throwsWithoutLookingUpMessage() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reportMessage("nope", 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(chatRepository, never()).findByIdAndChatroom(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-13] reportMessage()는 방의 구단이 응원 구단과 다르면 403을 던지고 "
            + "대상 메시지를 조회·blind하지 않는다")
    void reportMessage_teamMismatch_throwsAndNeverBlindsMessage() {
        Chatroom room = activeRoom(ROOM_UID, team(OTHER_TEAM_ID, "LG"));
        givenSupportTeam(2L, team());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_TEAM_MISMATCH);
        verify(chatRepository, never()).findByIdAndChatroom(any(), any());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15] reportMessage()는 응원 구단이 없으면 400을 던진다")
    void reportMessage_noSupportTeam_throwsSupportTeamRequired() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        givenNoSupportTeam(2L);

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);
    }
}
