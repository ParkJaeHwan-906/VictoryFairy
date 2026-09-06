package com.skhynix.user.profileimage.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.profileimage.policy.ProfileImageFormat;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 가입 요청이 실어 온 {@code temp/} EP 를 영구 경로로 옮긴다 — 계정이 생기는 순간에만 일어나는 전이다.
 *
 * <p>여기서 갈리는 두 실패의 처리가 이 기능의 핵심 결정이다.
 * <ul>
 *   <li><b>못 쓰는 EP</b>(모양이 어긋남·그 객체가 없음) → 400 으로 <b>가입을 막는다.</b> 남의 영구
 *       경로 EP 를 자기 계정에 붙이는 것을 막는 줄이고, 이미 소비된 EP 로 두 계정이 한 객체를
 *       공유하는 것도 여기서 걸린다</li>
 *   <li><b>이동 자체의 실패</b>(저장소 장애) → 가입을 <b>성공시키고 이미지만 포기한다.</b> 이메일
 *       인증 소비는 Redis 라 DB 롤백으로 되돌아오지 않아, 가입을 실패시키면 사용자가 이메일 인증부터
 *       다시 하도록 강요당한다 — 사진 한 장 때문에 치를 대가가 아니다</li>
 * </ul>
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. 이 빈은 가입 트랜잭션 안에서 호출되는데, 여기에
 * 트랜잭션 경계가 생기면 내부에서 잡아 삼킨 예외가 가입 트랜잭션을 rollback-only 로 오염시켜
 * "가입은 성공시킨다"는 위 결정이 조용히 깨진다.
 */
@Service
@RequiredArgsConstructor
public class SignupProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(SignupProfileImageService.class);

    private final ProfileImageStorage storage;

    /**
     * @param requestedEndpoint 가입 요청의 {@code profileImgUrl}. 선택 입력이라 {@code null} 이면
     *                          그대로 이미지 없는 계정이 된다(기존 가입 클라이언트가 그대로 동작한다)
     * @return 저장할 영구 EP. 이미지가 없거나 <b>이동에 실패</b>했으면 {@code null}
     * @throws BusinessException 모양이 어긋나거나 그 객체가 버킷에 없을 때({@code 400})
     */
    public String moveToPermanent(String requestedEndpoint) {
        if (requestedEndpoint == null) {
            return null;
        }
        // 빈 문자열·다른 접두·255자 초과·경로 조작은 전부 여기서 400 이다.
        ProfileImagePolicy.validateTempEndpoint(requestedEndpoint);

        try {
            if (!storage.exists(requestedEndpoint)) {
                throw new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT);
            }
            return move(requestedEndpoint);
        } catch (BusinessException e) {
            // "그 객체는 없다"는 판정은 확정된 사실이라 그대로 400 으로 올린다.
            throw e;
        } catch (RuntimeException e) {
            // 저장소에 닿지 못한 경우다. 존재 확인 단계에서 났더라도 "없음"으로 단정할 수 없으므로
            // 400 이 아니라 이동 실패로 다룬다 — 저장소 장애가 가입 거절이 되면 안 된다.
            log.error("프로필 이미지 이동 실패 — 가입은 진행하고 이미지는 비운다: ep={}",
                    requestedEndpoint, e);
            return null;
        }
    }

    /**
     * 복사 후 원본 삭제. S3 에는 이름 바꾸기가 없어 이 둘이 곧 "이동"이다(원자적이지 않다).
     *
     * <p>파일명은 원본을 물려받지 않고 새로 만든다 — {@code temp/} 는 CDN 으로 열려 있어 가입 전
     * 링크가 새어 나갈 수 있는데, 이름을 물려받으면 그 링크를 아는 사람이 가입 후 영구 EP 까지 그대로
     * 알게 된다. 확장자만 원본에서 가져오며 그 값은 업로드 시 선두 바이트로 판정된 형식이다.
     *
     * <p>원본 삭제가 실패해도 이동은 성공으로 본다 — 계정은 이미 새 객체를 가리키고 남은 {@code temp/}
     * 객체는 정리 스케줄러와 라이프사이클이 받아 간다.
     */
    private String move(String sourceEndpoint) {
        ProfileImageFormat format = ProfileImageFormat
                .fromExtension(ProfileImagePolicy.extensionOf(sourceEndpoint))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_ENDPOINT));

        String destination = ProfileImagePolicy.newKey(ProfileImagePolicy.PERMANENT_PREFIX, format);
        storage.copy(sourceEndpoint, destination);
        try {
            storage.delete(sourceEndpoint);
        } catch (RuntimeException e) {
            log.error("이동한 임시 프로필 이미지 삭제 실패 — temp 정리가 회수한다: ep={}",
                    sourceEndpoint, e);
        }
        return destination;
    }
}
