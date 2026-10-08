package com.skhynix.user.community.service;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.community.dto.CommunityImageResponse;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.service.ProfileImageContent;
import com.skhynix.user.profileimage.service.ProfileImageUploader;
import com.skhynix.user.profileimage.service.ProfileImageUploader.UploadErrorCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 선업로드 — 글에 붙기 전까지 이미지는 S3 {@code temp/} 에만 있고 DB 행은 없다. 횟수 한도가 없는 것은
 * 인증 경로라서다(프로필의 {@code /users/me/profile-image} 와 같은 판단).
 */
@Service
@RequiredArgsConstructor
public class CommunityImageService {

    // 판정은 프로필과 같고 거절 코드만 커뮤니티 것이다
    private static final UploadErrorCodes ERROR_CODES = new UploadErrorCodes(
            ErrorCode.COMMUNITY_IMAGE_REQUIRED, ErrorCode.COMMUNITY_IMAGE_TOO_LARGE,
            ErrorCode.INVALID_COMMUNITY_IMAGE_FORMAT);

    private final ProfileImageUploader uploader;

    public CommunityImageResponse upload(MultipartFile image) {
        ProfileImageContent content = uploader.validate(image, ERROR_CODES);
        return new CommunityImageResponse(uploader.store(content, ProfileImagePolicy.TEMP_PREFIX));
    }
}
