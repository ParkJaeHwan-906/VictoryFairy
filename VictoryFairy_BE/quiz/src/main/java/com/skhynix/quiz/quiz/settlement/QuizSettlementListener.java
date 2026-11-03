package com.skhynix.quiz.quiz.settlement;

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
 * py-collector(AI 레포)가 이닝 종료 시 쓰는 S3 {@code inning-events/{date}/{gameId}/{inning}-{half}.json}
 * 문서의 "생성됨" 알림을 받아 {@link QuizSettlementService#settleInningEvent}를 돈다.
 *
 * <p>전달 경로: S3 ObjectCreated 알림 → SQS(Infra 레포 소관, 큐 이름은 추정이라 URL 을 설정값으로
 * 분리) → 이 리스너가 {@code receiveMessage(waitTimeSeconds=20)} 롱폴링으로 가져온다.
 * {@code quiz.settlement.sqs-queue-url}이 비어 있으면(인프라가 아직 큐를 안 만든 환경) 폴링을
 * 시작하지 않는다 — 기동이 깨지면 안 된다.
 *
 * <p>처리 실패(파싱·정산 예외)는 메시지를 지우지 않고 다음 루프로 넘긴다 — SQS 가시성 제한 시간이
 * 지나면 재수신되고, {@link QuizSettlementService#settleInningEvent}는 {@code answer IS NULL}
 * 조건 덕에 멱등이라 재처리가 안전하다.
 *
 * <p>전용 데몬 스레드 하나로 순차 폴링한다(동시성 불필요 — 이닝 이벤트는 경기당 하루 10여 건
 * 수준). {@link #stop()}은 루프 플래그만 내린다 — {@code SqsClient}는 {@link QuizSettlementConfig}가
 * 만든 빈이라 Spring 컨테이너의 추론된 destroy 메서드({@code close()})가 별도로 정리한다.
 */
@Component
public class QuizSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(QuizSettlementListener.class);

    private static final int WAIT_TIME_SECONDS = 20;
    private static final int MAX_MESSAGES = 10;

    private final SqsClient sqsClient;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final QuizSettlementService settlementService;
    private final String queueUrl;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;

    public QuizSettlementListener(SqsClient sqsClient, S3Client s3Client, ObjectMapper objectMapper,
            QuizSettlementService settlementService,
            @Value("${quiz.settlement.sqs-queue-url:}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.settlementService = settlementService;
        this.queueUrl = queueUrl;
    }

    @PostConstruct
    public void start() {
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("quiz.settlement.sqs-queue-url 미설정 — 정산 리스너를 시작하지 않음"
                    + "(인프라가 큐를 만들기 전까지는 정상)");
            return;
        }
        running.set(true);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "quiz-settlement-listener");
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
                // 한 번의 폴링 실패가 리스너 스레드를 죽이면 그 뒤로 정산이 영구히 멈춘다 —
                // sweepUnresolved() 폴백이 있지만 지연을 최소화하려면 루프는 계속 돈다.
                log.error("정산 리스너 폴링 실패 — 다음 루프에서 재시도", e);
            }
        }
    }

    private void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_TIME_SECONDS)
                .maxNumberOfMessages(MAX_MESSAGES)
                .build();
        List<Message> messages = sqsClient.receiveMessage(request).messages();
        for (Message message : messages) {
            handle(message);
        }
    }

    private void handle(Message message) {
        try {
            for (S3ObjectRef ref : extractObjectRefs(message.body())) {
                InningEventFact fact = readFact(ref);
                settlementService.settleInningEvent(fact);
            }
            deleteMessage(message);
        } catch (RuntimeException e) {
            log.error("정산 메시지 처리 실패 — 메시지를 지우지 않고 재수신에 맡김: messageId={}",
                    message.messageId(), e);
        }
    }

    /**
     * S3 알림 JSON({@code Records[].s3.bucket.name}·{@code Records[].s3.object.key})에서 꺼낸다 —
     * 배치 알림이면 여러 건일 수 있다. 키는 S3 가 URL 인코딩해서 보내므로 디코딩한다.
     */
    private List<S3ObjectRef> extractObjectRefs(String body) {
        JsonNode root = objectMapper.readTree(body);
        JsonNode records = root.get("Records");
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

    private InningEventFact readFact(S3ObjectRef ref) {
        ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(ref.bucket()).key(ref.key()).build());
        return objectMapper.readValue(bytes.asByteArray(), InningEventFact.class);
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
