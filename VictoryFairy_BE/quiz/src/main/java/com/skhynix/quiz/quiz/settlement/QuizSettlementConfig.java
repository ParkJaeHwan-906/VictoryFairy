package com.skhynix.quiz.quiz.settlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * ⚠ prod 배포 전에 quiz 파드 ServiceAccount 에 그 큐에 대한 {@code sqs:ReceiveMessage}·
 * {@code sqs:DeleteMessage} IRSA 가 걸려 있어야 한다 — 인프라(dev_infra) 소관. 큐 자체(S3 알림 →
 * SQS)도 인프라가 만든다 — 이름은 추정값이라 {@code quiz.settlement.sqs-queue-url} 설정값으로
 * 분리해 뒀다({@code QuizSettlementListener} 참고).
 */
@Configuration
public class QuizSettlementConfig {

    @Bean
    public SqsClient quizSettlementSqsClient(
            @Value("${quiz.settlement.sqs-region}") String region) {
        // QuizIngestConfig.quizIngestS3Client 와 같은 이유로 빌드 시점 네트워크 접근이 없다 —
        // 자격증명·연결은 첫 요청에서 지연 해석된다.
        return SqsClient.builder().region(Region.of(region)).build();
    }
}
