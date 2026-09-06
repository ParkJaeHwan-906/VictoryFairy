package com.skhynix.user.profileimage.policy;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 프로필 이미지 EP(오브젝트 키)의 <b>모양과 크기 한도</b>에 관한 단일 출처.
 *
 * <p>EP 는 버킷 안 오브젝트 키다 — 스킴·도메인·버킷명을 담지 않고 선행 슬래시도 없다. 저장 값이
 * 이 형태라야 BaseURL(CloudFront 도메인)이 바뀌는 날 DB 를 통째로 UPDATE 하지 않아도 되고,
 * 클라이언트는 {@code https://victoryfairy.com/} 뒤에 그대로 이어 붙이기만 하면 된다.
 *
 * <p>세그먼트는 항상 둘이다({@code 접두/파일명}) — 하위 디렉터리를 만들지 않는 것이 CloudFront 의
 * 경로 패턴({@code /temp/*}·{@code /user-profile-img/*})과 1:1 로 맞물리는 근거다.
 */
public final class ProfileImagePolicy {

    /** 계정이 아직 없는 상태에서 올린 이미지가 머무는 자리. 정리 스케줄러·라이프사이클의 대상이다. */
    public static final String TEMP_PREFIX = "temp/";

    /** 계정에 실제로 매달린 이미지. <b>어떤 자동 정리 대상도 아니다.</b> */
    public static final String PERMANENT_PREFIX = "user-profile-img/";

    /**
     * 5MiB. 컨테이너 멀티파트 한도(application.yaml)는 이보다 넉넉해, 정확한 경계 판정은 실제 바이트
     * 길이를 재는 여기 한 곳에서만 한다 — 한도를 딱 5MiB 로 두면 멀티파트 오버헤드 때문에 5MiB 정확한
     * 파일까지 컨테이너 단에서 걸린다.
     */
    public static final int MAX_SIZE_BYTES = 5 * 1024 * 1024;

    /** {@code users_account.profile_img_url} 컬럼 길이와 같은 값. 저장 단계에서 잘리지 않게 앞에서 막는다. */
    public static final int MAX_ENDPOINT_LENGTH = 255;

    // 서버가 만든 EP 만 통과시키는 엄격한 패턴이다(UUID v4 + 허용 확장자 3종). 느슨하게 풀면
    // temp/a/b.png 같은 키가 그대로 영구 경로로 복사돼 세그먼트 2개 계약이 깨지고, ../ 류의 경로
    // 조작도 여기서 함께 막힌다.
    private static final Pattern TEMP_ENDPOINT = Pattern.compile(
            "temp/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    + "[.](jpg|png|webp)");

    private ProfileImagePolicy() {
    }

    /** 새 오브젝트 키. 파일명은 항상 서버가 만든다 — 원본 파일명은 어떤 조각도 쓰지 않는다. */
    public static String newKey(String prefix, ProfileImageFormat format) {
        return prefix + UUID.randomUUID() + "." + format.extension();
    }

    /**
     * 가입 요청이 실어 온 EP 가 <b>우리가 발급한 temp EP 의 모양</b>인지 본다. 존재 여부는 보지 않는다
     * (저장소를 아는 호출자 몫).
     *
     * <p>이 검사가 막는 것은 오타가 아니라 <b>남의 영구 경로 EP 를 자기 계정에 붙이는 일</b>이다 —
     * {@code user-profile-img/…} 는 여기서 걸린다.
     */
    public static void validateTempEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > MAX_ENDPOINT_LENGTH
                || !TEMP_ENDPOINT.matcher(endpoint).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);
        }
    }

    /**
     * 삭제해도 되는 값인가 — <b>영구 경로 접두</b>일 때만 참이다.
     *
     * <p>이미지 변경·탈퇴 두 삭제 경로가 공유하는 안전장치다. 컬럼에 어쩌다 다른 값이 들어가도
     * DeleteObject 가 그 값으로 나가지 않는다.
     */
    public static boolean isPermanentEndpoint(String endpoint) {
        return endpoint != null && endpoint.startsWith(PERMANENT_PREFIX);
    }

    /** EP 의 확장자(점 뒤). 형태 검증을 통과한 값에만 쓴다. */
    public static String extensionOf(String endpoint) {
        return endpoint.substring(endpoint.lastIndexOf('.') + 1);
    }
}
