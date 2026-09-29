package com.skhynix.user.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBlock;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBlockRepository;
import com.skhynix.user.block.dto.BlockResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link UserBlockService}를 협력 객체(리포지토리) 전부 목으로 대체해 단위로 검증한다. 요구사항:
 * {@code docs/requirements/user/user-block.md}(USER-BLK-1~12).
 *
 * <p>DB·스프링 컨텍스트 없음 — DB UNIQUE(uk_user_blocks_blocker_blocked) 자체가 지키는 race 방어
 * (USER-BLK-8/20)는 이 테스트로 증명할 수 없다(리포지토리 실기동 검증 대상). 여기서는 서비스가
 * existsBy 사전 확인 결과에 따라 save를 호출하는지/생략하는지, 그리고 판정 순서(미존재→자기 자신)를
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class UserBlockServiceTest {

    private static final Long BLOCKER_ID = 1L;
    private static final Long BLOCKED_ID = 2L;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserBlockRepository userBlockRepository;

    @InjectMocks
    private UserBlockService userBlockService;

    private static UserAccount accountOf(Long id, String nickname) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("encoded").build();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    // ---------- 차단 대상 미존재 (USER-BLK-4) ----------

    @Test
    @DisplayName("[USER-BLK-4] targetNickname에 해당하는 활성 계정이 없으면 BLOCK_TARGET_NOT_FOUND(404)를 "
            + "던지고 어떤 차단 행도 만들지 않는다")
    void block_targetNicknameNotFound_throwsBlockTargetNotFoundAndDoesNotSave() {
        // given
        given(userAccountRepository.findById(BLOCKER_ID))
                .willReturn(Optional.of(accountOf(BLOCKER_ID, "blocker")));
        given(userAccountRepository.findByNicknameAndExitAtIsNull("ghost"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userBlockService.block(BLOCKER_ID, "ghost"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BLOCK_TARGET_NOT_FOUND);

        verify(userBlockRepository, never()).save(any());
    }

    // ---------- 자기 자신 차단 (USER-BLK-5) ----------

    @Test
    @DisplayName("[USER-BLK-5] targetNickname이 요청자 자신의 현재 닉네임과 같으면 "
            + "SELF_BLOCK_NOT_ALLOWED(400)를 던지고 행을 만들지 않는다")
    void block_selfNickname_throwsSelfBlockNotAllowedAndDoesNotSave() {
        // given
        UserAccount self = accountOf(BLOCKER_ID, "홍길동");
        given(userAccountRepository.findById(BLOCKER_ID)).willReturn(Optional.of(self));
        given(userAccountRepository.findByNicknameAndExitAtIsNull("홍길동")).willReturn(Optional.of(self));

        // when & then
        assertThatThrownBy(() -> userBlockService.block(BLOCKER_ID, "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SELF_BLOCK_NOT_ALLOWED);

        verify(userBlockRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-BLK-4, 5] 판정 순서는 대상 미존재가 자기 자신 여부보다 앞선다 — 대상 자체가 없으면 "
            + "자기 자신 비교에 이르지 못하고 404로 끝난다")
    void block_targetNotFound_precedesSelfCheck() {
        // given
        given(userAccountRepository.findById(BLOCKER_ID))
                .willReturn(Optional.of(accountOf(BLOCKER_ID, "blocker")));
        given(userAccountRepository.findByNicknameAndExitAtIsNull("no-such-nickname"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userBlockService.block(BLOCKER_ID, "no-such-nickname"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BLOCK_TARGET_NOT_FOUND);
    }

    // ---------- 최초 차단 성공 (USER-BLK-6, 9) ----------

    @Test
    @DisplayName("[USER-BLK-6, 9] 차단 이력이 없는 대상을 차단하면 새 차단 행을 저장하고 대상 닉네임을 담은 "
            + "응답을 반환한다")
    void block_firstTimeTarget_savesNewBlockAndReturnsTargetNickname() {
        // given
        UserAccount blocker = accountOf(BLOCKER_ID, "blocker-nick");
        UserAccount blocked = accountOf(BLOCKED_ID, "홍길동");
        given(userAccountRepository.findById(BLOCKER_ID)).willReturn(Optional.of(blocker));
        given(userAccountRepository.findByNicknameAndExitAtIsNull("홍길동")).willReturn(Optional.of(blocked));
        given(userBlockRepository.existsByBlocker_IdAndBlocked_Id(BLOCKER_ID, BLOCKED_ID))
                .willReturn(false);

        // when
        BlockResponse response = userBlockService.block(BLOCKER_ID, "홍길동");

        // then
        assertThat(response.nickname()).isEqualTo("홍길동");
        verify(userBlockRepository).save(any(UserBlock.class));
    }

    // ---------- 중복 차단 멱등 (USER-BLK-7, 8) ----------

    @Test
    @DisplayName("[USER-BLK-7, 8] 이미 차단한 대상을 다시 차단 요청하면 새 행을 만들지 않고(existsBy로 "
            + "선판정) 200에 해당하는 정상 응답을 그대로 반환한다(예외가 아니다)")
    void block_alreadyBlockedTarget_doesNotSaveAndReturnsNormalResponse() {
        // given
        UserAccount blocker = accountOf(BLOCKER_ID, "blocker-nick");
        UserAccount blocked = accountOf(BLOCKED_ID, "홍길동");
        given(userAccountRepository.findById(BLOCKER_ID)).willReturn(Optional.of(blocker));
        given(userAccountRepository.findByNicknameAndExitAtIsNull("홍길동")).willReturn(Optional.of(blocked));
        given(userBlockRepository.existsByBlocker_IdAndBlocked_Id(BLOCKER_ID, BLOCKED_ID))
                .willReturn(true);

        // when
        BlockResponse response = userBlockService.block(BLOCKER_ID, "홍길동");

        // then
        assertThat(response.nickname()).isEqualTo("홍길동");
        verify(userBlockRepository, never()).save(any());
    }

    // ---------- 차단 목록 조회 (USER-BLK-10, 11) ----------

    @Test
    @DisplayName("[USER-BLK-10] 내가 차단한 대상 전원을 닉네임으로 매핑해 반환한다(나를 차단한 사람은 "
            + "리포지토리 조건(blocker=나)만으로 이미 제외돼 있다)")
    void getMyBlocks_returnsBlockedNicknamesInOrder() {
        // given
        UserBlock block1 = UserBlock.builder()
                .blocker(accountOf(BLOCKER_ID, "me"))
                .blocked(accountOf(2L, "A"))
                .build();
        UserBlock block2 = UserBlock.builder()
                .blocker(accountOf(BLOCKER_ID, "me"))
                .blocked(accountOf(3L, "B"))
                .build();
        UserBlock block3 = UserBlock.builder()
                .blocker(accountOf(BLOCKER_ID, "me"))
                .blocked(accountOf(4L, "C"))
                .build();
        given(userBlockRepository.findAllByBlocker_IdOrderByCreatedAtAsc(BLOCKER_ID))
                .willReturn(List.of(block1, block2, block3));

        // when
        List<BlockResponse> result = userBlockService.getMyBlocks(BLOCKER_ID);

        // then
        assertThat(result).extracting(BlockResponse::nickname).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("[USER-BLK-11] 차단 이력이 없으면 예외 없이 빈 목록을 반환한다")
    void getMyBlocks_noBlockHistory_returnsEmptyList() {
        // given
        given(userBlockRepository.findAllByBlocker_IdOrderByCreatedAtAsc(BLOCKER_ID))
                .willReturn(List.of());

        // when
        List<BlockResponse> result = userBlockService.getMyBlocks(BLOCKER_ID);

        // then
        assertThat(result).isEmpty();
    }

    // ---------- 요구사항 미기재 경계: 주체 계정이 그 사이 사라진 레이스 ----------

    @Test
    @DisplayName("[요구사항 미기재, 경계] 필터를 통과한 주체 id인데 계정을 못 찾으면(레이스) UNAUTHENTICATED"
            + "(401)를 던진다")
    void block_blockerAccountMissing_throwsUnauthenticated() {
        // given
        given(userAccountRepository.findById(BLOCKER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userBlockService.block(BLOCKER_ID, "홍길동"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        verify(userBlockRepository, never()).save(any());
    }
}
