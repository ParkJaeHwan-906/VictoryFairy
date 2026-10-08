package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.user.community.policy.CommunityImagePolicy;
import com.skhynix.user.profileimage.policy.ProfileImagePolicy;
import com.skhynix.user.profileimage.storage.ProfileImageStorage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 요청의 {@code imageUrls} 를 최종 EP 배열로 바꾼다 — 기존 확정 EP 는 유지, 임시 EP 는 {@code community/}
 * 로 이동. 작성·수정, 게시글·댓글이 전부 이 한 곳을 쓴다.
 *
 * <p><b>검증을 전부 끝낸 뒤에 이동을 시작한다.</b> 원소 하나가 400 이면 앞 원소의 객체가 이미 옮겨진
 * 상태가 남지 않게 하기 위해서다. 이동 자체의 실패(저장소 장애)는 그대로 올려 500 이 된다 — 가입과 달리
 * "이미지만 포기"하지 않는다(여기엔 롤백 안 되는 선행 효과가 없다).
 *
 * <p>{@code @Transactional} 이 없는 것은 의도다 — 외부 호출(S3)을 DB 트랜잭션 밖에 둔다. 호출자는 이
 * 결과를 받아 <b>그 다음에</b> 트랜잭션을 연다(S3 복사가 DB 쓰기보다 먼저라 DB 쓰기가 실패하면
 * {@code community/} 고아 객체가 남을 수 있다 — 알려진 한계).
 */
@Component
@RequiredArgsConstructor
public class CommunityImageAttacher {

    private static final Logger log = LoggerFactory.getLogger(CommunityImageAttacher.class);

    private final ProfileImageStorage storage;

    /**
     * @param requested 요청의 {@code imageUrls}(null 불가, 빈 배열 가능)
     * @param existing  이 글·댓글에 이미 붙어 있는 확정 EP — 작성 시엔 빈 집합
     * @param max       첨부 상한(게시글 5·댓글 3)
     * @return 요청 순서 그대로의 최종 EP 목록
     */
    public List<String> attach(List<String> requested, Set<String> existing, int max) {
        // 상한이 모양보다 앞이라 6개짜리 요청은 S3 를 조회하지 않는다
        if (requested.size() > max) {
            throw new BusinessException(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED);
        }
        Set<String> seen = new HashSet<>();
        List<String> temps = new ArrayList<>();
        for (String endpoint : requested) {
            // null·빈 문자열·중복은 전부 같은 400 — 한 객체를 두 번 이동할 수 없다(첫 이동이 원본을 지운다)
            if (endpoint == null || endpoint.isEmpty() || !seen.add(endpoint)) {
                throw new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT);
            }
            if (existing.contains(endpoint)) {
                continue;
            }
            // 남의 글의 community/ EP·프로필 EP·전체 URL·경로 조작은 전부 여기서 걸린다 — 이 글의 기존 EP 가
            // 아니면 서버가 발급한 temp 모양만 통과한다.
            if (!ProfileImagePolicy.isTempEndpoint(endpoint)) {
                throw new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT);
            }
            // "없음"은 모양 오류와 같은 문구다 — 사유를 나누면 남의 temp EP 존재 여부를 탐색할 수 있다.
            // 저장소에 닿지 못한 경우는 exists 가 예외로 올려 400 이 아니라 500 이 된다.
            if (!storage.exists(endpoint)) {
                throw new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT);
            }
            temps.add(endpoint);
        }

        List<String> result = new ArrayList<>(requested.size());
        for (String endpoint : requested) {
            result.add(temps.contains(endpoint) ? move(endpoint) : endpoint);
        }
        return result;
    }

    /**
     * 복사 후 원본 삭제(S3 에는 이름 바꾸기가 없다). 복사 실패는 그대로 올린다. 원본 삭제 실패는 이동
     * 성공으로 본다 — 글은 이미 새 객체를 가리키고 남은 {@code temp/} 객체는 정리 스케줄러가 받아 간다.
     * Content-Type 은 복사 기본 지시자가 원본 그대로 따라오게 한다.
     */
    private String move(String tempEndpoint) {
        String destination = CommunityImagePolicy.toPermanent(tempEndpoint);
        storage.copy(tempEndpoint, destination);
        try {
            storage.delete(tempEndpoint);
        } catch (RuntimeException e) {
            log.error("이동한 임시 커뮤니티 이미지 삭제 실패 — temp 정리가 회수한다: ep={}", tempEndpoint, e);
        }
        return destination;
    }
}
