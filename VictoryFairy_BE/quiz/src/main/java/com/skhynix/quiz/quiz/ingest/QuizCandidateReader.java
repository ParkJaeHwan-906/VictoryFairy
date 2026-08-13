package com.skhynix.quiz.quiz.ingest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.ObjectMapper;

@Component
public class QuizCandidateReader {

    private static final Logger log = LoggerFactory.getLogger(QuizCandidateReader.class);

    private static final String PREFIX = "quiz-candidates/";

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucket;

    public QuizCandidateReader(S3Client s3Client, ObjectMapper objectMapper,
            @Value("${quiz.ingest.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
    }

    public List<QuizCandidate> readCandidates(LocalDate date) {
        String prefix = PREFIX + date + "/";
        List<QuizCandidate> candidates = new ArrayList<>();
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        // 파티션당 20건 남짓이라 페이지네이션 iterator 로 충분(1페이지 1000키 한도에 한참 못 미침)
        for (var page : s3Client.listObjectsV2Paginator(listRequest)) {
            for (S3Object object : page.contents()) {
                if (!object.key().endsWith(".json")) {
                    continue;
                }
                readOne(object.key()).ifPresent(candidates::add);
            }
        }
        return candidates;
    }

    private java.util.Optional<QuizCandidate> readOne(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return java.util.Optional.of(
                    objectMapper.readValue(bytes.asByteArray(), QuizCandidate.class));
        } catch (RuntimeException e) {
            log.warn("후보 파일 읽기/파싱 실패 — 이 파일만 건너뜀: s3://{}/{}", bucket, key, e);
            return java.util.Optional.empty();
        }
    }
}
