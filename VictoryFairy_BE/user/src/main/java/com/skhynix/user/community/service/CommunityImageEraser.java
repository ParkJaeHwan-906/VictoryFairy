package com.skhynix.user.community.service;

import com.skhynix.user.community.policy.CommunityImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 더 이상 참조되지 않는 {@code community/} 객체를 지우는 <b>best-effort</b> 경로 — 글·댓글 삭제와 수정에서
 * 빠진 EP 가 여기로 온다. 실패를 예외로 올리지 않는다(본 작업은 이미 커밋됐고 남은 것은 뒷정리다 —
 * {@code ProfileImageEraser} 와 같은 성격). ⚠ 반드시 <b>커밋 뒤에</b> 불러야 한다 — 트랜잭션 안에서 지우면
 * 롤백됐을 때 글은 여전히 그 EP 를 가리키는데 객체만 사라진다.
 */
@Component
@RequiredArgsConstructor
public class CommunityImageEraser {

    private static final Logger log = LoggerFactory.getLogger(CommunityImageEraser.class);

    private final ProfileImageStorage storage;

    public void eraseQuietly(Collection<String> endpoints) {
        for (String endpoint : endpoints) {
            eraseQuietly(endpoint);
        }
    }

    public void eraseQuietly(String endpoint) {
        if (!CommunityImagePolicy.isPermanentEndpoint(endpoint)) {
            // 확정 경로가 아닌 값이 행에 있다는 것 자체가 이상 신호다 — 그 값으로 삭제를 내보내지 않는 것이
            // 잘못된 값 하나로 남의 객체를 지우는 사고를 막는 마지막 줄이다.
            log.warn("확정 경로가 아닌 커뮤니티 이미지 값 — 삭제하지 않는다: ep={}", endpoint);
            return;
        }
        try {
            storage.delete(endpoint);
        } catch (RuntimeException e) {
            log.error("커뮤니티 이미지 객체 삭제 실패 — 참조 없는 객체로 남는다(수동 회수 대상): ep={}",
                    endpoint, e);
        }
    }
}
