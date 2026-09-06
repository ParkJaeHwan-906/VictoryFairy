package com.skhynix.user.profileimage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 프로필 이미지 저장소(S3) 클라이언트 등록 — quiz 의 {@code QuizIngestConfig} 와 같은 방식이다.
 *
 * <p><b>자격증명을 코드·설정에 두지 않는다.</b> 빌더에 아무것도 주지 않으면
 * {@code DefaultCredentialsProvider} 가 로컬에서는 {@code ~/.aws} 를, EKS 파드에서는 IRSA 가 넣어 준
 * 웹 아이덴티티 토큰을 스스로 찾는다. 그래서 같은 이미지가 어느 환경에서도 그대로 돈다.
 *
 * <p>⚠ IRSA 경로는 {@code software.amazon.awssdk:sts} 가 클래스패스에 있어야 성립한다(build.gradle
 * 의 {@code runtimeOnly}). 빠뜨리면 로컬({@code ~/.aws})에서는 멀쩡하다가 파드에서만 자격증명 로드가
 * 실패한다 — 빌드·기동은 통과하고 첫 업로드에서야 5xx 로 드러난다.
 *
 * <p>⚠ prod 배포 전에 파드 ServiceAccount 에 버킷 쓰기 권한(PutObject·GetObject·CopyObject·
 * DeleteObject·ListBucket)이 걸려 있어야 한다 — 인프라(dev_infra) 소관이다.
 */
@Configuration
public class ProfileImageStorageConfig {

    @Bean
    public S3Client profileImageS3Client(@Value("${user.profile-image.region}") String region) {
        // 빌드·기동 시점에는 네트워크 접근이 없다 — 자격증명과 연결은 첫 호출에서 지연 해석되므로
        // 자격증명 없는 환경(테스트 등)에서도 컨텍스트 기동은 깨지지 않는다.
        return S3Client.builder().region(Region.of(region)).build();
    }
}
