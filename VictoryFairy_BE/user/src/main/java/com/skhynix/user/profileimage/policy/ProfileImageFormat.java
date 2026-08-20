package com.skhynix.user.profileimage.policy;

import java.util.Optional;

/**
 * 허용 이미지 형식의 <b>단일 출처</b>(PasswordPolicy·NicknamePolicy 와 같은 구조). 허용 목록·판정
 * 방법·저장 Content-Type·확장자가 전부 이 enum 안에서만 정해진다.
 *
 * <p><b>판정은 파일 선두 바이트(매직 넘버)로 한다.</b> 확장자와 요청 {@code Content-Type} 은 둘 다
 * 클라이언트가 자유로이 정하는 값이라, 그것을 믿으면 실행 파일을 {@code a.png} +
 * {@code image/png} 로 위장해 버킷에 넣을 수 있다.
 *
 * <p>HEIC 는 의도적으로 없다 — iOS 기본 촬영 포맷을 앱(RN)이 JPEG 로 변환해 보내는 것이 전제이고,
 * 변환하지 않고 그대로 보내면 400 이다. 여기에 항목을 추가하는 것은 계약 변경이다.
 */
public enum ProfileImageFormat {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String contentType;
    private final String extension;

    ProfileImageFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /**
     * 선두 바이트로 형식을 판정한다. 셋 중 어느 것도 아니면 비어 있는 값 — 호출자가 400 으로 옮긴다.
     */
    public static Optional<ProfileImageFormat> detect(byte[] content) {
        if (isJpeg(content)) {
            return Optional.of(JPEG);
        }
        if (isPng(content)) {
            return Optional.of(PNG);
        }
        if (isWebp(content)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** 확장자로 형식을 되찾는다 — 이미 이 정책으로 저장된 EP 의 확장자에만 쓴다. */
    public static Optional<ProfileImageFormat> fromExtension(String extension) {
        for (ProfileImageFormat format : values()) {
            if (format.extension.equals(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    // FF D8 FF — SOI 마커. 뒤따르는 세그먼트 종류(E0/E1/DB…)는 보지 않는다.
    private static boolean isJpeg(byte[] c) {
        return c.length >= 3 && u(c[0]) == 0xFF && u(c[1]) == 0xD8 && u(c[2]) == 0xFF;
    }

    // 89 50 4E 47 0D 0A 1A 0A — 8바이트 고정 시그니처.
    private static boolean isPng(byte[] c) {
        return c.length >= 8 && u(c[0]) == 0x89 && c[1] == 'P' && c[2] == 'N' && c[3] == 'G'
                && u(c[4]) == 0x0D && u(c[5]) == 0x0A && u(c[6]) == 0x1A && u(c[7]) == 0x0A;
    }

    // RIFF....WEBP — 4~7바이트는 파일 크기라 건너뛰고 8~11바이트의 'WEBP' 까지 봐야 한다
    // (RIFF 만 보면 WAV·AVI 도 통과한다).
    private static boolean isWebp(byte[] c) {
        return c.length >= 12 && c[0] == 'R' && c[1] == 'I' && c[2] == 'F' && c[3] == 'F'
                && c[8] == 'W' && c[9] == 'E' && c[10] == 'B' && c[11] == 'P';
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
