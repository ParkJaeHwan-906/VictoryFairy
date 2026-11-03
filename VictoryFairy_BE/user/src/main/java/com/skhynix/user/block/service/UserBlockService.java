package com.skhynix.user.block.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBlock;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBlockRepository;
import com.skhynix.user.block.dto.BlockResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 간 차단 생성·목록 조회({@code docs/requirements/user/user-block.md} USER-BLK-1~12). 해제
 * (unblock)는 이번 스코프에 없다 — 한번 생긴 차단 행은 계정 하드 삭제(CASCADE, USER-BLK-21) 전까지
 * 영구히 남는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserBlockService {

    private final UserAccountRepository userAccountRepository;
    private final UserBlockRepository userBlockRepository;

    /**
     * 판정 순서 ①차단 주체 조회 ②대상 닉네임의 활성 계정 조회(USER-BLK-4) ③자기 자신 여부(USER-BLK-5).
     * 셋을 통과하면 기존 차단 존재 여부를 보고 없을 때만 새 행을 만든다 — 이미 차단한 대상 재요청은
     * 에러가 아니라 멱등 200 이다(USER-BLK-7).
     */
    public BlockResponse block(Long userAccountId, String targetNickname) {
        UserAccount blocker = loadAccount(userAccountId);

        UserAccount blocked = userAccountRepository.findByNicknameAndExitAtIsNull(targetNickname)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOCK_TARGET_NOT_FOUND));

        if (blocker.getId().equals(blocked.getId())) {
            throw new BusinessException(ErrorCode.SELF_BLOCK_NOT_ALLOWED);
        }

        // DB UNIQUE(uk_user_blocks_blocker_blocked)가 경합(race)의 최종 방어선이다(USER-BLK-8/20).
        // 이 존재 확인은 경합이 없는 정상 경로에서 불필요한 INSERT 시도를 피하기 위한 사전 확인일 뿐,
        // 이미 있으면 새 행을 만들지 않고 그대로 성공 응답만 돌려준다.
        if (!userBlockRepository.existsByBlocker_IdAndBlocked_Id(blocker.getId(), blocked.getId())) {
            userBlockRepository.save(UserBlock.builder().blocker(blocker).blocked(blocked).build());
        }

        return new BlockResponse(blocked.getNickname());
    }

    /**
     * 내가 차단한 대상 전원, 차단한 순서대로(USER-BLK-10) — 나를 차단한 사람은 포함하지 않는다(저장이
     * blocker → blocked 단방향이라 blocker 조건만으로 이미 그렇다). 차단 이력이 없으면 빈 목록(USER-BLK-11).
     */
    @Transactional(readOnly = true)
    public List<BlockResponse> getMyBlocks(Long userAccountId) {
        return userBlockRepository.findAllByBlocker_IdOrderByCreatedAtAsc(userAccountId).stream()
                .map(block -> new BlockResponse(block.getBlocked().getNickname()))
                .toList();
    }

    // 필터가 활성 계정임을 확인한 id 라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
    // 사라진 것이므로 UserProfileEditService.loadAccount 와 같은 401 로 맞춘다.
    private UserAccount loadAccount(Long userAccountId) {
        return userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
