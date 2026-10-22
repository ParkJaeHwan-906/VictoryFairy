package com.skhynix.user.game.realtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * py-collector(AI 레포)가 경기의 이닝·점수·상태가 바뀔 때 쓰는 S3
 * {@code game-state-events/{date}/{gameId}/{observedAt}.json}의 "생성됨" 알림을 받아, 그 경기를 DB 에서
 * 다시 읽어({@link GameUpdateService}) 모든 파드의 구독자에게 발행한다({@link GameEventPublisher}).
 *
 * <p>전달 경로: S3 ObjectCreated 알림 → SQS(Infra 레포 소관) → 이 리스너가
 * {@code receiveMessage(waitTimeSeconds=20)} 롱폴링으로 가져온다. {@code user.game-events.sqs-queue-url}이
 * 비어 있으면(인프라가 아직 큐를 안 만든 환경·로컬) 폴링을 시작하지 않는다 — 기동이 깨지면 안 된다.
 * quiz 의 {@code QuizSettlementListener}와 같은 뼈대다.
 *
 * <p>처리 실패는 메시지를 지우지 않고 다음 루프로 넘긴다 — SQS 가시성 제한 시간이 지나면 재수신되고, 같은
 * 스냅샷을 두 번 발행해도 구독자 화면은 같다(멱등). 경기 행이 없는 알림은 실패가 아니라 "건너뜀"이라
 * 지운다 — 재수신해도 결과가 같다.
 *
 * <p>전용 데몬 스레드 하나로 순차 폴링한다(경기 시간대 분당 수 건 수준). HPA 로 파드가 2개면 두 파드가 같은
 * 큐를 나눠 받고, 받은 파드가 Redis 로 발행해 나머지 파드의 구독자에게도 간다.
 */
@Component
public class GameStateEventListener {

    private static final Logger log = LoggerFactory.getLogger(GameStateEventListener.class);

    private static final int WAIT_TIME_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;

    private final SqsClient sqsClient;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final GameUpdateService updateService;
    private final GameEventPublisher publisher;
    private final String queueUrl;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public GameStateEventListener(@Qualifier("gameEventSqsClient") SqsClient sqsClient,
            @Qualifier("gameEventS3Client") S3Client s3Client, ObjectMapper objectMapper,
            GameUpdateService updateService, GameEventPublisher publisher,
            @Value("${user.game-events.sqs-queue-url:}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.updateService = updateService;
        this.publisher = publisher;
        this.queueUrl = queueUrl;
    }

    @PostConstruct
    public void start() {
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("user.game-events.sqs-queue-url 미설정 — 경기 상태 리스너를 시작하지 않음"
                    + "(인프라가 큐를 만들기 전까지는 정상)");
            return;
        }
        running.set(true);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "game-state-event-listener");
            thread.setDaemon(true);
            return thread;
        });
        executor.submit(this::pollLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                poll();
            } catch (RuntimeException e) {
                // 한 번의 폴링 실패가 리스너 스레드를 죽이면 그 뒤로 실시간 갱신이 영구히 멈춘다.
                log.error("경기 상태 리스너 폴링 실패 — 다음 루프에서 재시도", e);
            }
        }
    }

    private void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_TIME_SECONDS)
                .maxNumberOfMessages(MAX_MESSAGES)
                .build();
        for (Message message : sqsClient.receiveMessage(request).messages()) {
            handle(message);
        }
    }

    void handle(Message message) {
        try {
            for (S3ObjectRef ref : extractObjectRefs(message.body())) {
                GameStateEventDocument document = readDocument(ref);
                updateService.load(document).ifPresentOrElse(publisher::publish,
                        () -> log.warn("경기 행 없음 — 갱신 건너뜀 gameId={} key={}", document.gameId(), ref.key()));
            }
            deleteMessage(message);
        } catch (RuntimeException e) {
            log.error("경기 상태 메시지 처리 실패 — 메시지를 지우지 않고 재수신에 맡김: messageId={}",
                    message.messageId(), e);
        }
    }

    /**
     * S3 알림 JSON({@code Records[].s3.bucket.name}·{@code Records[].s3.object.key})에서 꺼낸다 — 배치 알림이면
     * 여러 건일 수 있다. 키는 S3 가 URL 인코딩해서 보내므로 디코딩한다.
     */
    private List<S3ObjectRef> extractObjectRefs(String body) {
        JsonNode records = objectMapper.readTree(body).get("Records");
        if (records == null || !records.isArray()) {
            return List.of();
        }
        List<S3ObjectRef> refs = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            JsonNode s3Node = records.get(i).get("s3");
            String bucket = textField(s3Node == null ? null : s3Node.get("bucket"), "name");
            String rawKey = textField(s3Node == null ? null : s3Node.get("object"), "key");
            if (bucket != null && rawKey != null) {
                refs.add(new S3ObjectRef(bucket, URLDecoder.decode(rawKey, StandardCharsets.UTF_8)));
            }
        }
        return refs;
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString("");
        return text.isBlank() ? null : text;
    }

    private GameStateEventDocument readDocument(S3ObjectRef ref) {
        ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(ref.bucket()).key(ref.key()).build());
        return objectMapper.readValue(bytes.asByteArray(), GameStateEventDocument.class);
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private record S3ObjectRef(String bucket, String key) {
    }
}
