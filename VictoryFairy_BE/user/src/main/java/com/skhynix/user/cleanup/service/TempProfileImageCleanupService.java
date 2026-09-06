package com.skhynix.user.cleanup.service;

import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import com.skhynix.user.profileimage.storage.StoredObject;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 가입까지 이어지지 않은 임시 프로필 이미지를 지우는 한 회차.
 *
 * <p>{@code temp/} 객체는 어떤 계정에도 저장되지 않으므로(가입은 영구 경로로 옮긴 EP 만 저장한다)
 * <b>DB 참조 여부를 확인하지 않는다</b> — 확인 조회를 넣으면 회차마다 전체 스캔이 붙는데 그 조회는
 * 언제나 "참조 없음"만 돌려준다.
 *
 * <p>{@code @Transactional} 이 없고 DB 를 아예 보지 않는다(만료 데이터 정리와 다른 점이다).
 */
@Service
@RequiredArgsConstructor
public class TempProfileImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TempProfileImageCleanupService.class);

    /**
     * 이만큼 지난 객체만 지운다.
     *
     * <p>0 이 아닌 이유는 <b>가입 화면을 열어 둔 사용자</b> 때문이다 — 사진을 고른 뒤 인증·입력을
     * 마치는 동안 그 객체가 회차에 쓸려 나가면 미리보기가 깨지고 가입도 400 이 된다.
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    private final ProfileImageStorage storage;

    /**
     * @param baseTime 회차 기준 시각. 호출자가 {@code Clock} 빈에서 한 번만 읽어 넘긴다 —
     *                 목록 순회 중에 "지금"을 다시 읽으면 앞뒤 객체가 서로 다른 기준으로 판정된다
     * @return 삭제한 객체 수
     */
    public int removeExpiredTempImages(Instant baseTime) {
        log.info("임시 프로필 이미지 정리 시작 — 기준 시각 {}", baseTime);

        // 마지막 수정 + 24시간 <= 기준 시각 을 뒤집은 값이다.
        Instant threshold = baseTime.minus(RETENTION);

        int deleted = 0;
        int failed = 0;
        // 목록 조회는 temp/ 접두로 한정한다. 영구 경로(user-profile-img/)는 이 회차가 아예 보지
        // 않는다 - 여기에 접두를 하나 더 넣으면 살아 있는 계정의 프로필 사진이 조용히 사라진다.
        for (StoredObject object : storage.list(ProfileImagePolicy.TEMP_PREFIX)) {
            if (object.lastModified().isAfter(threshold)) {
                continue;
            }
            try {
                storage.delete(object.key());
                deleted++;
            } catch (RuntimeException e) {
                // 한 객체의 실패는 그 객체에서 끝난다. 다음 날 회차가 같은 대상을 다시 집고,
                // 그마저 못 돌면 버킷 라이프사이클(temp/ 1일 만료)이 2차로 받아 간다.
                failed++;
                log.error("임시 프로필 이미지 삭제 실패 — 이 객체만 건너뜀: ep={}", object.key(), e);
            }
        }

        log.info("임시 프로필 이미지 정리 완료 — 기준 시각 {}: 삭제 {}건, 실패 {}건",
                baseTime, deleted, failed);
        return deleted;
    }
}
