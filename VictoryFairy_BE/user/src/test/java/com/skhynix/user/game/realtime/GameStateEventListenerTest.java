package com.skhynix.user.game.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.user.game.dto.GameResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link GameStateEventListener#handle(Message)} 단위 테스트 — S3 알림에서 키를 꺼내 문서를 읽고, 경기를 다시
 * 읽어 발행한 뒤 메시지를 지운다. 폴링 루프 자체(스레드)는 여기서 돌리지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class GameStateEventListenerTest {

    private static final String QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/1/q";

    /** S3 가 SQS 로 보내는 알림 본문의 최소 형태. 키는 URL 인코딩돼 온다(콜론 → %3A). */
    private static final String NOTIFICATION = """
            {"Records":[{"s3":{"bucket":{"name":"victoryfairy-crawl-dev"},
             "object":{"key":"game-state-events/2026-08-01/20260801LGHT02026/20260801T093000000000Z.json"}}}]}
            """;

    private static final String DOCUMENT = """
            {"gameId":"20260801LGHT02026","date":"2026-08-01","status":"IN_PROGRESS",
             "homeScore":3,"awayScore":5,"inning":7,"inningHalf":"TOP","lastInning":7,
             "changed":["homeScore"],"observedAt":"2026-08-01T09:30:00+00:00","unknownField":1}
            """;

    @Mock
    private SqsClient sqsClient;

    @Mock
    private S3Client s3Client;

    @Mock
    private GameUpdateService updateService;

    @Mock
    private GameEventPublisher publisher;

    private GameStateEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new GameStateEventListener(sqsClient, s3Client, new ObjectMapper(), updateService,
                publisher, QUEUE_URL);
    }

    private void stubDocument() {
        given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), DOCUMENT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("알림의 키로 S3 문서를 읽고, 경기를 다시 읽어 발행한 뒤 메시지를 지운다 — 모르는 필드는 무시한다")
    void handle_readsDocument_publishesUpdate_andDeletesMessage() {
        stubDocument();
        GameUpdateEvent event = new GameUpdateEvent(new GameResponse("20260801LGHT02026", "잠실야구장", "LG", 3L,
                "KIA", 7L, 3, 5, LocalDateTime.of(2026, 8, 1, 18, 30), "IN_PROGRESS", null, 7, "TOP"),
                List.of("homeScore"), "2026-08-01T09:30:00+00:00");
        ArgumentCaptor<GameStateEventDocument> document = ArgumentCaptor.forClass(GameStateEventDocument.class);
        given(updateService.load(document.capture())).willReturn(Optional.of(event));

        listener.handle(Message.builder().messageId("m1").receiptHandle("rh-1").body(NOTIFICATION).build());

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("victoryfairy-crawl-dev");
        assertThat(request.getValue().key())
                .isEqualTo("game-state-events/2026-08-01/20260801LGHT02026/20260801T093000000000Z.json");
        assertThat(document.getValue().gameId()).isEqualTo("20260801LGHT02026");
        assertThat(document.getValue().changed()).containsExactly("homeScore");
        verify(publisher).publish(event);
        ArgumentCaptor<DeleteMessageRequest> delete = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(delete.capture());
        assertThat(delete.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(delete.getValue().receiptHandle()).isEqualTo("rh-1");
    }

    @Test
    @DisplayName("경기 행이 없으면 발행하지 않지만 메시지는 지운다 — 재수신해도 결과가 같다")
    void handle_unknownGame_skipsPublish_butDeletes() {
        stubDocument();
        given(updateService.load(any())).willReturn(Optional.empty());

        listener.handle(Message.builder().messageId("m2").receiptHandle("rh-2").body(NOTIFICATION).build());

        verifyNoInteractions(publisher);
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("처리 중 예외가 나면 메시지를 지우지 않는다 — SQS 가시성 시간 뒤 재수신에 맡긴다")
    void handle_failure_doesNotDelete() {
        stubDocument();
        given(updateService.load(any())).willThrow(new IllegalStateException("db down"));

        listener.handle(Message.builder().messageId("m3").receiptHandle("rh-3").body(NOTIFICATION).build());

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Records 가 없는 본문(테스트 이벤트 등)은 아무것도 읽지 않고 메시지만 지운다")
    void handle_bodyWithoutRecords_deletesOnly() {
        listener.handle(Message.builder().messageId("m4").receiptHandle("rh-4")
                .body("{\"Event\":\"s3:TestEvent\"}").build());

        verifyNoInteractions(s3Client, updateService, publisher);
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }
}
