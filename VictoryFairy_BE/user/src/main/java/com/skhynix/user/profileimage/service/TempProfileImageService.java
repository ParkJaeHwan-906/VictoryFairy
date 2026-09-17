package com.skhynix.user.profileimage.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.store.ProfileImageUploadLimitStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 계정이 아직 없는 시점의 업로드 — 가입 화면에서 사진을 고르는 순간에는 인증할 주체가 없다.
 *
 * <p>그래서 이 서비스가 실제로 지키는 것은 이미지가 아니라 <b>인증 없이 열린 쓰기 창구</b>다:
 * {@code appId} 별 횟수 제한이 유일한 문지기이며, DB 행은 하나도 만들지 않는다(남는 것은 S3 객체와
 * Redis 카운터뿐).
 *
 * <p>⚠ 이 한도는 남용을 막는 장치가 아니다 — {@code appId} 는 클라이언트가 발급하므로 새 값을
 * 만들면 다시 10회가 열린다. 정상 사용자의 반복 시도를 막을 뿐이고 실효 방어는 인프라 몫이다.
 */
@Service
@RequiredArgsConstructor
public class TempProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(TempProfileImageService.class);

    /** 한 창(30분) 안에 허용하는 성공 횟수. 10회까지 허용하고 11번째가 거절된다. */
    private static final int MAX_UPLOADS_PER_WINDOW = 10;

    private final ProfileImageUploader uploader;
    private final ProfileImageUploadLimitStore limitStore;

    /**
     * 순서가 계약이다: {@code appId} 확인 → <b>이미지 검증</b> → 카운터 증가 → 저장(실패하면 환불).
     *
     * <ul>
     *   <li>검증이 증가보다 앞이라, 형식·크기 위반으로 400 을 받은 요청은 한도를 소모하지 않는다</li>
     *   <li>증가가 저장보다 앞이라, 한도 초과 요청은 객체를 만들지 않고 거절된다. Redis 가 죽어
     *       증가에 실패해도 마찬가지로 객체가 생기지 않는다(fail-closed)</li>
     *   <li>저장이 실패하면 방금 소모한 1회를 되돌린다 — 객체가 하나도 안 생긴 회차가 슬롯을
     *       태우지 않게 한다</li>
     * </ul>
     *
     * <p><b>"저장 성공 후 증가"로 순서를 바꾸지 말 것.</b> 원자적 증가로 게이트를 잡는 것이 동시 요청
     * 둘이 나란히 통과해 한도가 뚫리는 것을 막는 설계다 — 저장 뒤로 미루면 판정과 증가 사이에 창이
     * 생긴다. 순서는 그대로 두고 실패분만 환불하는 것이 이 문제의 해법이다(S3 실패 10회가 정상
     * 사용자의 슬롯 10개를 조용히 태우는 것이 실측된 자리다).
     *
     * <p>거절된 요청도 카운터를 올린다는 점은 의도된 것이다 — 이미 한도를 넘긴 창에서 값이 11, 12 로
     * 늘어도 판정 결과는 같고, TTL 은 최초 생성 때만 걸리므로 창이 밀리지도 않는다. <b>한도 초과
     * 거절은 환불 대상이 아니다.</b>
     */
    public ProfileImageResponse upload(String appId, MultipartFile image) {
        if (appId == null || appId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_APP_ID);
        }
        ProfileImageContent content = uploader.validate(image);

        if (limitStore.increment(appId) > MAX_UPLOADS_PER_WINDOW) {
            throw new BusinessException(ErrorCode.PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED);
        }

        // appId 는 키·파일명·응답 어디에도 실리지 않는다 — 남의 appId 를 알아도 그가 올린 EP 를
        // 유도할 수 없어야 한다.
        String endpoint;
        try {
            endpoint = uploader.store(content, ProfileImagePolicy.TEMP_PREFIX);
        } catch (RuntimeException e) {
            refundQuietly(appId);
            // 사용자에게 나가야 하는 것은 저장 실패 그대로다. 환불은 부수 작업일 뿐이라 원인 예외를
            // 가리면 안 된다.
            throw e;
        }
        return new ProfileImageResponse(endpoint);
    }

    /**
     * 환불은 <b>best-effort</b> 다({@code ProfileImageEraser.eraseQuietly} 와 같은 성격) — 실패해도
     * 예외를 올리지 않고 로그만 남긴다.
     *
     * <p>여기서 예외를 던지면 저장 실패(원인)가 환불 실패(결과)에 가려져 장애 분석이 불가능해진다.
     * 환불이 안 된 최악의 결과는 그 {@code appId} 가 슬롯 1개를 잃는 것뿐이고, 창이 30분이라 저절로
     * 회복된다.
     */
    private void refundQuietly(String appId) {
        try {
            limitStore.refund(appId);
        } catch (RuntimeException e) {
            log.error("임시 업로드 한도 환불 실패 — 이 appId 는 이번 창에서 1회를 잃는다(30분 뒤 회복)", e);
        }
    }
}
