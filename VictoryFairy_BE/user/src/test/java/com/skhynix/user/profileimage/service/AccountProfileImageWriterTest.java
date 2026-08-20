package com.skhynix.user.profileimage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AccountProfileImageWriter} — 프로필 이미지 갱신의 트랜잭션 단위. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-11, 70.
 *
 * <p>여기서 검증할 핵심은 <b>직전 EP를 {@code changeProfileImgUrl()} 호출 전에 챙긴다</b>는 순서다 —
 * 뒤집으면 직전 값을 영원히 잃어버려 옛 객체를 지울 방법이 없어진다(USER-PI-70의 실제 강제 지점).
 */
@ExtendWith(MockitoExtension.class)
class AccountProfileImageWriterTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private AccountProfileImageWriter writer;

    private UserAccount newAccount() {
        return UserAccount.builder()
                .nickname("nick")
                .password("encoded")
                .build();
    }

    @Test
    @DisplayName("[USER-PI-11] 직전 이미지가 없던 계정이면 replace는 null을 돌려주고 컬럼은 새 EP로 바뀐다")
    void replace_noPreviousImage_returnsNullAndUpdatesColumn() {
        // given
        UserAccount account = newAccount();
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(account));

        // when
        String previous = writer.replace(1L, "user-profile-img/new.png");

        // then
        assertThat(previous).isNull();
        assertThat(account.getProfileImgUrl()).isEqualTo("user-profile-img/new.png");
    }

    @Test
    @DisplayName("[USER-PI-70] 직전 이미지가 있던 계정이면 replace는 changeProfileImgUrl 호출 전 값(직전 "
            + "EP)을 그대로 돌려주고, 계정 컬럼은 새 EP로 바뀐다 — 반환값이 곧 옛 객체를 알아낼 유일한 자리다")
    void replace_previousImageExisted_returnsPreviousEndpointBeforeOverwrite() {
        // given
        UserAccount account = newAccount();
        account.changeProfileImgUrl("user-profile-img/old.png");
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(account));

        // when
        String previous = writer.replace(1L, "user-profile-img/new.png");

        // then
        assertThat(previous).isEqualTo("user-profile-img/old.png");
        assertThat(account.getProfileImgUrl()).isEqualTo("user-profile-img/new.png");
    }

    @Test
    @DisplayName("계정이 사라졌으면(정상 경로 밖) 필터가 못 찾았을 때와 같은 UNAUTHENTICATED를 던진다")
    void replace_accountNotFound_throwsUnauthenticated() {
        given(userAccountRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.replace(999L, "user-profile-img/new.png"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
}
