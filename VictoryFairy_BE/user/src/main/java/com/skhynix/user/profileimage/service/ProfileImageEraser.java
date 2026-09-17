package com.skhynix.user.profileimage.service;

import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 더 이상 참조되지 않는 프로필 이미지 객체를 지우는 <b>best-effort</b> 경로. 이미지 변경(직전 객체)과
 * 탈퇴(그 계정의 객체) 두 호출자가 같은 규칙을 공유한다.
 *
 * <p><b>실패를 예외로 올리지 않는다.</b> 두 호출자 모두 "본래 하려던 일(이미지 교체·탈퇴)은 이미
 * 끝났고, 남은 것은 뒷정리"인 지점에서 부른다 — 뒷정리 실패로 본 작업을 되돌리면 사용자는 자기가
 * 요청한 일이 안 된 것으로 본다. 대신 실패한 EP 를 ERROR 로 남겨 사람이 회수할 수 있게 한다
 * (재시도·보류 큐는 만들지 않는다 — 회수는 수동이다).
 */
@Component
@RequiredArgsConstructor
public class ProfileImageEraser {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageEraser.class);

    private final ProfileImageStorage storage;

    /**
     * @param endpoint 지울 EP. {@code null}(이미지가 없던 계정)이면 <b>아무 일도 하지 않고 로그도
     *                 남기지 않는다</b> — 지울 것이 없는 것은 이상 상황이 아니다
     * @return 실제로 DeleteObject 를 보내고 성공했으면 참
     */
    public boolean eraseQuietly(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        if (!ProfileImagePolicy.isPermanentEndpoint(endpoint)) {
            // 영구 경로가 아닌 값이 컬럼에 들어 있다는 것 자체가 이상 신호다. 그 값으로 삭제를
            // 내보내지 않는 것이 잘못된 값 하나로 남의 객체를 지우는 사고를 막는 마지막 줄이다.
            log.warn("영구 경로가 아닌 프로필 이미지 값 — 삭제하지 않는다: ep={}", endpoint);
            return false;
        }
        try {
            storage.delete(endpoint);
            return true;
        } catch (RuntimeException e) {
            log.error("프로필 이미지 객체 삭제 실패 — 참조 없는 객체로 남는다(수동 회수 대상): ep={}",
                    endpoint, e);
            return false;
        }
    }
}
