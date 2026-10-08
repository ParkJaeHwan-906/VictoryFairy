package com.skhynix.user.community.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link CommunityImagePolicy} 와 신규 COMMUNITY_* ErrorCode 13건(USER-CM-148, 152, 200). */
class CommunityImagePolicyAndErrorCodeTest {

    @Test
    @DisplayName("[AC-CM-147-1, AC-CM-148-1] toPermanent는 temp/ 접두만 community/ 로 바꾸고 파일명은 그대로 둔다(세그먼트 2개)")
    void toPermanent_swapsPrefixKeepingFileName() {
        String permanent = CommunityImagePolicy.toPermanent("temp/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.webp");

        assertThat(permanent).isEqualTo("community/9f1c3a52-7d1e-4b8a-9c3e-1a2b3c4d5e6f.webp");
        assertThat(permanent.split("/")).hasSize(2);
    }

    @Test
    @DisplayName("[AC-CM-96-1] isPermanentEndpoint는 community/ 접두에만 참이다 - temp·프로필·null은 삭제 대상이 아니다")
    void isPermanentEndpoint_onlyCommunityPrefix() {
        assertThat(CommunityImagePolicy.isPermanentEndpoint("community/a.jpg")).isTrue();
        assertThat(CommunityImagePolicy.isPermanentEndpoint("temp/a.jpg")).isFalse();
        assertThat(CommunityImagePolicy.isPermanentEndpoint("user-profile-img/a.jpg")).isFalse();
        assertThat(CommunityImagePolicy.isPermanentEndpoint("/community/a.jpg")).isFalse();
        assertThat(CommunityImagePolicy.isPermanentEndpoint(null)).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-152-1] 첨부 상한은 게시글 5, 댓글·답글 3이고 ErrorCode 문구의 5/3과 같은 값이다")
    void limits_matchErrorCodeMessage() {
        assertThat(CommunityImagePolicy.MAX_POST_IMAGES).isEqualTo(5);
        assertThat(CommunityImagePolicy.MAX_COMMENT_IMAGES).isEqualTo(3);
        assertThat(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED.getMessage())
                .contains(CommunityImagePolicy.MAX_POST_IMAGES + "장")
                .contains(CommunityImagePolicy.MAX_COMMENT_IMAGES + "장");
    }

    @Test
    @DisplayName("[AC-CM-200-1, AC-CM-200-2] 신규 ErrorCode 13건의 상태코드·문구가 계약과 같다(410은 이 저장소 최초)")
    void communityErrorCodes_matchContract() {
        assertCode(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND, 404, "존재하지 않는 카테고리입니다.");
        assertCode(ErrorCode.COMMUNITY_POST_NOT_FOUND, 404, "존재하지 않는 게시글입니다.");
        assertCode(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND, 404, "존재하지 않는 댓글입니다.");
        assertCode(ErrorCode.COMMUNITY_POST_BLINDED, 410, "신고로 숨김 처리된 게시글입니다.");
        assertCode(ErrorCode.COMMUNITY_COMMENT_BLINDED, 410, "신고로 숨김 처리된 댓글입니다.");
        assertCode(ErrorCode.COMMUNITY_NOT_AUTHOR, 403, "작성자만 수정·삭제할 수 있습니다.");
        assertCode(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED, 403, "자신의 글은 신고할 수 없습니다.");
        assertCode(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED, 400, "답글에는 답글을 달 수 없습니다.");
        assertCode(ErrorCode.COMMUNITY_IMAGE_REQUIRED, 400, "이미지를 첨부해 주세요.");
        assertCode(ErrorCode.INVALID_COMMUNITY_IMAGE_FORMAT, 400, "JPG, PNG, WEBP 이미지만 업로드할 수 있습니다.");
        assertCode(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT, 400, "유효하지 않은 이미지입니다.");
        assertCode(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED, 400, "이미지는 게시글 5장, 댓글 3장까지 첨부할 수 있습니다.");
        assertCode(ErrorCode.COMMUNITY_IMAGE_TOO_LARGE, 413, "이미지 크기는 5MB를 넘을 수 없습니다.");
    }

    @Test
    @DisplayName("[AC-CM-171-1] 채팅의 SELF_REPORT_NOT_ALLOWED 문구는 바뀌지 않았다(커뮤니티가 재사용하지 않는 이유)")
    void chatSelfReportCode_isUnchanged() {
        assertThat(ErrorCode.SELF_REPORT_NOT_ALLOWED.getMessage()).startsWith("자신의 메시지");
        assertThat(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED).isNotEqualTo(ErrorCode.SELF_REPORT_NOT_ALLOWED);
    }

    private static void assertCode(ErrorCode code, int status, String message) {
        assertThat(code.getStatus()).as(code.name()).isEqualTo(status);
        assertThat(code.getMessage()).as(code.name()).isEqualTo(message);
    }
}
