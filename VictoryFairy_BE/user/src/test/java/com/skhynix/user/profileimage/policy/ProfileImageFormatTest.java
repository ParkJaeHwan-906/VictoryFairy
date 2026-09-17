package com.skhynix.user.profileimage.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProfileImageFormat#detect(byte[])} — 매직 넘버 기반 형식 판정. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-35~37, 44, 46.
 */
class ProfileImageFormatTest {

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @Test
    @DisplayName("[USER-PI-35, 37] FF D8 FF로 시작하는 바이트는 JPEG로 판정된다")
    void detect_jpegMagicBytes_returnsJpeg() {
        byte[] content = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10);

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).contains(ProfileImageFormat.JPEG);
    }

    @Test
    @DisplayName("[USER-PI-35, 37] 8바이트 PNG 시그니처(89 50 4E 47 0D 0A 1A 0A)는 PNG로 판정된다")
    void detect_pngMagicBytes_returnsPng() {
        byte[] content = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00);

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).contains(ProfileImageFormat.PNG);
    }

    @Test
    @DisplayName("[USER-PI-35, 37] RIFF....WEBP 시그니처는 WEBP로 판정된다(4~7바이트 파일 크기는 건너뛴다)")
    void detect_webpMagicBytes_returnsWebp() {
        byte[] content = "RIFF????WEBPVP8 ".getBytes();

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).contains(ProfileImageFormat.WEBP);
    }

    @Test
    @DisplayName("[USER-PI-37] RIFF로 시작하지만 WEBP가 아닌 파일(WAV)은 통과하지 못한다"
            + "(RIFF만 보면 WAV·AVI도 통과하는 함정을 막는 회귀)")
    void detect_riffButNotWebp_wavFile_returnsEmpty() {
        byte[] content = "RIFF????WAVEfmt ".getBytes();

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).isEmpty();
    }

    @Test
    @DisplayName("[USER-PI-36] GIF 시그니처(GIF89a)는 허용 목록 밖이라 비어 있는 값으로 판정된다")
    void detect_gifMagicBytes_returnsEmpty() {
        byte[] content = "GIF89a".getBytes();

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).isEmpty();
    }

    @Test
    @DisplayName("[USER-PI-37] 실행 파일(ELF 매직 넘버)에 확장자만 .png로 위장해도 선두 바이트로 걸러진다"
            + "(확장자·Content-Type은 판정 근거가 아니라는 계약의 직접 증거)")
    void detect_disguisedExecutableWithPngExtension_returnsEmpty() {
        // 0x7F 'E' 'L' 'F' — ELF 실행 파일의 매직 넘버. 파일명·Content-Type과 무관하게 이 바이트만 본다.
        byte[] content = bytes(0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00);

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).isEmpty();
    }

    @Test
    @DisplayName("PDF 매직 넘버(%PDF)는 허용 목록 밖이라 비어 있는 값으로 판정된다")
    void detect_pdfMagicBytes_returnsEmpty() {
        byte[] content = "%PDF-1.4".getBytes();

        Optional<ProfileImageFormat> detected = ProfileImageFormat.detect(content);

        assertThat(detected).isEmpty();
    }

    @Test
    @DisplayName("빈 바이트 배열은 어떤 형식으로도 판정되지 않는다")
    void detect_emptyBytes_returnsEmpty() {
        assertThat(ProfileImageFormat.detect(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("[USER-PI-42, 44] 각 형식의 확장자는 정책이 정한 허용 확장자(jpg·png·webp)와 일치한다")
    void extension_matchesAllowedExtensions() {
        assertThat(ProfileImageFormat.JPEG.extension()).isEqualTo("jpg");
        assertThat(ProfileImageFormat.PNG.extension()).isEqualTo("png");
        assertThat(ProfileImageFormat.WEBP.extension()).isEqualTo("webp");
    }

    @Test
    @DisplayName("[USER-PI-46] 각 형식의 Content-Type이 표준 MIME 타입과 일치한다")
    void contentType_matchesStandardMimeTypes() {
        assertThat(ProfileImageFormat.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(ProfileImageFormat.PNG.contentType()).isEqualTo("image/png");
        assertThat(ProfileImageFormat.WEBP.contentType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("fromExtension은 허용 확장자를 각 형식으로 되찾고, 허용 밖 확장자는 비어 있는 값이다")
    void fromExtension_roundTripsAllowedExtensions_andRejectsUnknown() {
        assertThat(ProfileImageFormat.fromExtension("jpg")).contains(ProfileImageFormat.JPEG);
        assertThat(ProfileImageFormat.fromExtension("png")).contains(ProfileImageFormat.PNG);
        assertThat(ProfileImageFormat.fromExtension("webp")).contains(ProfileImageFormat.WEBP);
        assertThat(ProfileImageFormat.fromExtension("gif")).isEmpty();
    }
}
