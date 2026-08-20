package com.skhynix.user.profileimage.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProfileImagePolicy} — EP 모양·크기 한도의 단일 출처. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-41~45, 55, 56, 60, 61, 77.
 */
class ProfileImagePolicyTest {

    private static final String VALID_TEMP_ENDPOINT =
            "temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg";

    @Test
    @DisplayName("[USER-PI-42, 43] newKey는 {접두}{UUID v4}.{확장자} 형태를 만들고 세그먼트가 둘뿐이다")
    void newKey_producesTwoSegmentKeyWithUuidV4() {
        String key = ProfileImagePolicy.newKey(ProfileImagePolicy.TEMP_PREFIX, ProfileImageFormat.JPEG);

        assertThat(key).startsWith("temp/").endsWith(".jpg");
        assertThat(key.split("/")).hasSize(2);
        String uuidPart = key.substring("temp/".length(), key.length() - ".jpg".length());
        assertThat(uuidPart).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("[USER-PI-4, 97] newKey가 만드는 EP는 BaseURL 접두사를 포함하지 않는다"
            + "(https://·http://·버킷명·s3.·victoryfairy.com·선행 슬래시 전부 없음)")
    void newKey_neverContainsBaseUrlPrefix() {
        String key = ProfileImagePolicy.newKey(ProfileImagePolicy.PERMANENT_PREFIX, ProfileImageFormat.WEBP);

        assertThat(key)
                .doesNotStartWith("/")
                .doesNotContain("https://")
                .doesNotContain("http://")
                .doesNotContain("victoryfairy-asset")
                .doesNotContain("victoryfairy.com")
                .doesNotContain("s3.");
    }

    @Test
    @DisplayName("[USER-PI-45] newKey를 두 번 호출하면 서로 다른 키가 나온다(내용 기반 중복 제거 없음)")
    void newKey_calledTwice_producesDifferentKeys() {
        String first = ProfileImagePolicy.newKey(ProfileImagePolicy.PERMANENT_PREFIX, ProfileImageFormat.PNG);
        String second = ProfileImagePolicy.newKey(ProfileImagePolicy.PERMANENT_PREFIX, ProfileImageFormat.PNG);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("[USER-PI-55] 우리가 발급한 모양의 temp EP는 검증을 통과한다(예외를 던지지 않는다)")
    void validateTempEndpoint_wellFormedTempEndpoint_doesNotThrow() {
        assertThatCode(() -> ProfileImagePolicy.validateTempEndpoint(VALID_TEMP_ENDPOINT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[USER-PI-55] user-profile-img/ 접두(영구 경로 EP)는 400 INVALID_PROFILE_IMAGE_ENDPOINT다"
            + " — 남의 영구 경로 EP를 자기 계정에 붙이는 것을 막는 자리")
    void validateTempEndpoint_permanentPrefix_throwsInvalidEndpoint() {
        assertInvalidEndpoint("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }

    @Test
    @DisplayName("[USER-PI-55] 경로 조작(../)이 섞인 값도 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void validateTempEndpoint_pathTraversal_throwsInvalidEndpoint() {
        assertInvalidEndpoint("../x");
    }

    @Test
    @DisplayName("[USER-PI-55] 전체 URL(https://...)이 실려 와도 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void validateTempEndpoint_fullUrl_throwsInvalidEndpoint() {
        assertInvalidEndpoint("https://victoryfairy.com/temp/x.png");
    }

    @Test
    @DisplayName("[USER-PI-55] 빈 문자열은 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void validateTempEndpoint_emptyString_throwsInvalidEndpoint() {
        assertInvalidEndpoint("");
    }

    @Test
    @DisplayName("[USER-PI-61] 255자를 넘는 EP는 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void validateTempEndpoint_tooLong_throwsInvalidEndpoint() {
        String longEndpoint = "temp/" + "a".repeat(300) + ".jpg";
        assertInvalidEndpoint(longEndpoint);
    }

    @Test
    @DisplayName("[USER-PI-43] 하위 디렉터리가 섞인 temp 키(temp/a/b.png)는 세그먼트 2개 계약을 어겨 거절된다")
    void validateTempEndpoint_extraPathSegment_throwsInvalidEndpoint() {
        assertInvalidEndpoint("temp/a/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.png");
    }

    @Test
    @DisplayName("허용 확장자 밖(.gif)인 값은 형태 자체가 어긋나 400 INVALID_PROFILE_IMAGE_ENDPOINT다")
    void validateTempEndpoint_disallowedExtension_throwsInvalidEndpoint() {
        assertInvalidEndpoint("temp/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.gif");
    }

    private void assertInvalidEndpoint(String endpoint) {
        assertThatThrownBy(() -> ProfileImagePolicy.validateTempEndpoint(endpoint))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);
    }

    @Test
    @DisplayName("[USER-PI-77] 영구 경로 접두일 때만 isPermanentEndpoint가 참이다")
    void isPermanentEndpoint_onlyTrueForPermanentPrefix() {
        assertThat(ProfileImagePolicy.isPermanentEndpoint("user-profile-img/x.jpg")).isTrue();
        assertThat(ProfileImagePolicy.isPermanentEndpoint("temp/x.jpg")).isFalse();
        assertThat(ProfileImagePolicy.isPermanentEndpoint(null)).isFalse();
    }

    @Test
    @DisplayName("extensionOf는 점 뒤의 확장자를 그대로 돌려준다")
    void extensionOf_returnsPartAfterLastDot() {
        assertThat(ProfileImagePolicy.extensionOf(VALID_TEMP_ENDPOINT)).isEqualTo("jpg");
    }
}
