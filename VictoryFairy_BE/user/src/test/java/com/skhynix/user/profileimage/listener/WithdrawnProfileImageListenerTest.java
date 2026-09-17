package com.skhynix.user.profileimage.listener;

import static org.mockito.Mockito.verify;

import com.skhynix.user.account.event.UserWithdrawnEvent;
import com.skhynix.user.profileimage.service.ProfileImageEraser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link WithdrawnProfileImageListener} — 탈퇴 이벤트를 받아 프로필 이미지 객체를 지우는 지점.
 * 요구사항: {@code docs/requirements/user/profile-image.md} USER-PI-74.
 *
 * <p>이 리스너는 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}이라 실제 커밋 이후 호출
 * 여부는 스프링 트랜잭션 동기화가 하는 일이고 목 기반 유닛으로는 검증할 수 없다 — 여기서 고정하는 것은
 * "이벤트를 받으면 그 안의 EP로 eraser를 부른다"는 리스너 메서드 자체의 동작뿐이다(탈퇴 서비스가 EP를
 * 담은 이벤트를 발행하는지는 {@code UserAccountServiceTest} 소관으로 나눠 잡는다).
 */
@ExtendWith(MockitoExtension.class)
class WithdrawnProfileImageListenerTest {

    @Mock
    private ProfileImageEraser eraser;

    @InjectMocks
    private WithdrawnProfileImageListener listener;

    @Test
    @DisplayName("[USER-PI-74] 이벤트에 담긴 profileImgUrl 그대로 eraser.eraseQuietly를 호출한다")
    void eraseProfileImage_withEndpoint_delegatesToEraser() {
        UserWithdrawnEvent event =
                new UserWithdrawnEvent("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");

        listener.eraseProfileImage(event);

        verify(eraser).eraseQuietly("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }

    @Test
    @DisplayName("이미지가 없던 계정의 이벤트(profileImgUrl=null)도 그대로 eraser에 넘긴다 "
            + "— null 처리는 eraser의 계약이라 리스너가 따로 분기하지 않는다")
    void eraseProfileImage_withNullEndpoint_stillDelegatesToEraser() {
        UserWithdrawnEvent event = new UserWithdrawnEvent(null);

        listener.eraseProfileImage(event);

        verify(eraser).eraseQuietly(null);
    }
}
