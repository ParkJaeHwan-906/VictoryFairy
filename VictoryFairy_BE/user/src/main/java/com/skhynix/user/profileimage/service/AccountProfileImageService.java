package com.skhynix.user.profileimage.service;

import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 인증된 사용자의 프로필 이미지 등록·변경. <b>업로드가 곧 변경 확정</b>이며 취소·확정 단계가 없다.
 *
 * <p>대상 계정은 언제나 access 토큰의 주체다 — 경로·본문 어디에도 계정 식별자가 없어 타인의 이미지를
 * 바꿀 파라미터 자체가 존재하지 않는다. 임시 경로를 경유하지 않고 처음부터 영구 경로에 쓴다.
 *
 * <p>이 클래스에 {@code @Transactional} 이 없는 것은 실수가 아니다 — 세 단계의 순서가 곧 계약이다.
 */
@Service
@RequiredArgsConstructor
public class AccountProfileImageService {

    private final ProfileImageUploader uploader;
    private final AccountProfileImageWriter writer;
    private final ProfileImageEraser eraser;

    /**
     * ①저장 → ②컬럼 교체(트랜잭션) → ③직전 객체 삭제(커밋 이후, best-effort).
     *
     * <ul>
     *   <li>①이 실패하면 컬럼은 손대지 않은 채 5xx 다 — 저장에 실패한 이미지가 프로필이 되는 일은 없다</li>
     *   <li>③이 ②의 커밋 뒤인 것이 중요하다. 순서를 뒤집으면 컬럼 갱신이 실패했을 때 계정이 여전히
     *       가리키고 있는 객체를 지운 상태가 된다(사용자 화면에서 사진이 깨진다)</li>
     *   <li>③의 실패는 응답을 바꾸지 않는다(200). 남는 것은 참조 없는 옛 객체뿐이다</li>
     * </ul>
     *
     * <p>②가 실패하면 방금 올린 ①의 객체가 참조 없이 남는다 — 회수하지 않는다(자동 회수 경로가 없는
     * 것은 영구 경로 전체에 공통이다).
     */
    public ProfileImageResponse upload(Long userAccountId, MultipartFile image) {
        String newEndpoint = uploader.upload(image, ProfileImagePolicy.PERMANENT_PREFIX);

        String previousEndpoint = writer.replace(userAccountId, newEndpoint);

        eraser.eraseQuietly(previousEndpoint);

        return new ProfileImageResponse(newEndpoint);
    }
}
