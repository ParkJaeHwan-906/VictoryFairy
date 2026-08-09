package com.skhynix.quiz.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 퀴즈 적재용 빈. 자격증명을 코드·설정에 두지 않는 것이 규칙이다 —
 * {@code DefaultCredentialsProvider}가 로컬에선 {@code ~/.aws}(aws configure), prod 파드에선
 * IRSA(ServiceAccount 연결 IAM 역할)를 자동으로 집는다. ⚠ prod 배포 전에 quiz 파드
 * ServiceAccount 에 {@code quiz-candidates/*} 읽기(s3:GetObject + ListBucket) IRSA 가 걸려
 * 있어야 한다 — 인프라(dev_infra) 소관.
 */
@Configuration
public class QuizIngestConfig {

    @Bean
    public S3Client quizIngestS3Client(@Value("${quiz.ingest.region}") String region) {
        // 빌드 시점엔 네트워크 접근이 없다 — 자격증명·연결은 첫 요청에서 지연 해석되므로
        // 자격증명 없는 환경(테스트 등)에서도 컨텍스트 기동은 깨지지 않는다
        return S3Client.builder().region(Region.of(region)).build();
    }

    /**
     * KST 고정 클록. "오늘의 퀴즈"와 적재 대상 날짜의 '오늘'은 전부 KST 다 — 파드 JVM 은 TZ 미설정
     * (UTC)이라 {@code LocalDate.now()} 기본값을 쓰면 자정~09시 사이에 하루가 어긋난다
     * (game_date 9시간 밀림 사고와 같은 계열, application-prod.yaml 주석 참고). 테스트는 이 빈을
     * {@code Clock.fixed}로 갈아끼운다.
     */
    @Bean
    public Clock kstClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
