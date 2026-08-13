package com.skhynix.quiz.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * ⚠ prod 배포 전에 quiz 파드 ServiceAccount 에 {@code quiz-candidates/*} 읽기(s3:GetObject +
 * ListBucket) IRSA 가 걸려 있어야 한다 — 인프라(dev_infra) 소관.
 */
@Configuration
public class QuizIngestConfig {

    @Bean
    public S3Client quizIngestS3Client(@Value("${quiz.ingest.region}") String region) {
        // 빌드 시점엔 네트워크 접근이 없다 — 자격증명·연결은 첫 요청에서 지연 해석되므로
        // 자격증명 없는 환경(테스트 등)에서도 컨텍스트 기동은 깨지지 않는다
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    public Clock kstClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
