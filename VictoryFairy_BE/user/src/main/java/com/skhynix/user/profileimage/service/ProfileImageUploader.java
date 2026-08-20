package com.skhynix.user.profileimage.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.policy.ProfileImageFormat;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 두 업로드 경로가 공유하는 <b>검증 + 저장</b>. 저장 위치(접두)만 호출자가 정한다.
 *
 * <p>검증({@link #validate})과 저장({@link #store})이 나뉘어 있는 것은 임시 업로드 때문이다 — 형식
 * 위반으로 거절되는 요청이 한도 카운터를 소모하면 안 되므로, 카운터를 올리기 <b>전에</b> 400 이 될
 * 요청을 모두 걸러야 한다.
 */
@Component
@RequiredArgsConstructor
public class ProfileImageUploader {

    private final ProfileImageStorage storage;

    /** 검증부터 저장까지 한 번에 — 한도를 세지 않는 인증 업로드 경로가 쓴다. */
    public String upload(MultipartFile image, String prefix) {
        return store(validate(image), prefix);
    }

    /**
     * 파트 존재 → 크기 → 형식 순으로 본다. 크기가 형식보다 앞인 것은 판정 비용 때문이 아니라,
     * 5MiB 를 넘는 요청에는 형식 이야기를 할 이유가 없기 때문이다.
     */
    ProfileImageContent validate(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            // 파트 이름이 다른 경우(image 가 아닌 file 등)도 여기로 흡수된다 — 컨트롤러가 파트를
            // required=false 로 받아 "없음"과 "이름이 다름"을 같은 400 으로 만든다.
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_REQUIRED);
        }
        byte[] bytes = read(image);
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_REQUIRED);
        }
        // 경계의 단일 출처는 여기다. 컨테이너 멀티파트 한도는 넉넉하게 잡아 명백한 초과만 거르는
        // 1차 방어이고, "5MiB 정확히는 통과"는 이 비교가 보장한다.
        if (bytes.length > ProfileImagePolicy.MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_TOO_LARGE);
        }
        ProfileImageFormat format = ProfileImageFormat.detect(bytes)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_FORMAT));
        return new ProfileImageContent(bytes, format);
    }

    /**
     * 받은 바이트를 <b>변형 없이</b> 저장한다(리사이즈·재인코딩·EXIF 제거를 하지 않는다).
     *
     * @return 저장된 EP
     */
    String store(ProfileImageContent content, String prefix) {
        String key = ProfileImagePolicy.newKey(prefix, content.format());
        storage.put(key, content.bytes(), content.format().contentType());
        return key;
    }

    private byte[] read(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (IOException e) {
            // 클라이언트 잘못이 아니라 요청 본문을 다 읽지 못한 상황이다 — 400 으로 바꾸지 않는다.
            throw new UncheckedIOException("프로필 이미지를 읽지 못했다", e);
        }
    }
}
