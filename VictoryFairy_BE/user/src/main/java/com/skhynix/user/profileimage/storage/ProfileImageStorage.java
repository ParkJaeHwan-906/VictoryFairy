package com.skhynix.user.profileimage.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 프로필 이미지 버킷에 대한 유일한 접근 지점. 위(서비스)에서는 EP 문자열만 다루고 버킷 이름·SDK
 * 타입은 이 클래스 밖으로 나가지 않는다.
 *
 * <p><b>실패를 삼키지 않는다.</b> 어떤 호출이 best-effort 인지는 호출자마다 다르므로(업로드 실패는
 * 5xx, 옛 객체 삭제 실패는 로그) 판단을 여기서 하지 않는다.
 *
 * <p>⚠ 버킷 이름은 로그·응답 어디에도 싣지 않는다 — 프라이빗 버킷의 이름 자체를 노출하지 않는
 * 것이 계약이다.
 */
@Component
public class ProfileImageStorage {

    private final S3Client s3Client;
    private final String bucket;

    public ProfileImageStorage(S3Client profileImageS3Client,
            @Value("${user.profile-image.bucket}") String bucket) {
        this.s3Client = profileImageS3Client;
        this.bucket = bucket;
    }

    /**
     * 객체를 만든다. 응답을 받은 시점에 객체는 이미 버킷에 있다(S3 PutObject 는 성공 응답 후
     * 즉시 읽힌다) — 업로드 응답과 실제 저장 사이에 관측 가능한 공백이 없다는 계약의 근거다.
     *
     * <p>{@code contentType} 은 요청이 보낸 값이 아니라 <b>선두 바이트로 판정한 형식</b>이어야 한다.
     * CloudFront 가 이 값을 그대로 응답 헤더로 내보내므로 브라우저 렌더링이 여기에 달려 있다.
     */
    public void put(String key, byte[] content, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(), RequestBody.fromBytes(content));
    }

    /**
     * 객체가 실재하는가.
     *
     * <p>⚠ <b>"없음"과 "확인 불가"를 구분한다.</b> 404 만 {@code false} 이고, 자격증명·네트워크·권한
     * 문제는 예외로 그대로 올라간다 — 저장소 장애를 "그런 EP 는 없다"로 접으면 멀쩡한 이미지를 400
     * 으로 거절하게 된다.
     */
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * 같은 버킷 안에서 객체를 복사한다. 메타데이터(Content-Type 포함)는 기본 지시자(COPY)에 따라
     * 원본 그대로 따라간다 — 복사본에 Content-Type 을 다시 지정하지 않는 이유다.
     */
    public void copy(String sourceKey, String destinationKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(destinationKey)
                .build());
    }

    /** 객체 삭제. S3 DeleteObject 는 멱등이라 이미 없는 키에도 예외 없이 성공한다. */
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * 접두사 아래 객체 목록. 1페이지 1000키 한도를 넘길 수 있으므로 반드시 페이지네이터로 돈다 —
     * 단발 {@code listObjectsV2} 로 바꾸면 1000건까지만 지워지고 나머지가 조용히 쌓인다.
     */
    public List<StoredObject> list(String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        for (var page : s3Client.listObjectsV2Paginator(request)) {
            for (S3Object object : page.contents()) {
                objects.add(new StoredObject(object.key(), object.lastModified()));
            }
        }
        return objects;
    }
}
