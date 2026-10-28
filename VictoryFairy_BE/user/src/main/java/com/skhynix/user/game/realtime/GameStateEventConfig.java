package com.skhynix.user.game.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 경기 상태 변화 큐(SQS)와 그 메시지가 가리키는 S3 문서를 읽는 클라이언트.
 *
 * <p>자격증명은 코드·설정에 없다 — {@code ProfileImageStorageConfig}와 같은 방식으로 로컬은 {@code ~/.aws},
 * EKS 파드는 IRSA 다. ⚠ prod 배포 전에 user-app ServiceAccount 에 큐 소비(ReceiveMessage·DeleteMessage·
 * GetQueueAttributes)와 crawl 버킷 {@code game-state-events/*} GetObject 가 걸려 있어야 한다
 * (dev_infra {@code modules/user-irsa}). 큐 자체(S3 알림 → SQS)도 인프라가 만든다 — 이름은 인프라 쪽
 * 값이라 {@code user.game-events.sqs-queue-url} 설정으로 분리해 둔다.
 *
 * <p>S3 클라이언트를 프로필 이미지용({@code profileImageS3Client})과 따로 두는 이유: 버킷도 권한도 다르고,
 * 타입 하나로 두 빈을 주입받으면 한쪽 설정 변경이 다른 쪽을 흔든다. 빌드·기동 시점에 네트워크 접근은 없다.
 */
@Configuration
public class GameStateEventConfig {

    @Bean
    public SqsClient gameEventSqsClient(@Value("${user.game-events.sqs-region}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public S3Client gameEventS3Client(@Value("${user.game-events.sqs-region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }
}
