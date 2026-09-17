package com.skhynix.user.profileimage.listener;

import com.skhynix.user.account.event.UserWithdrawnEvent;
import com.skhynix.user.profileimage.service.ProfileImageEraser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 탈퇴한 계정의 프로필 이미지 객체를 지운다.
 *
 * <p>{@code AFTER_COMMIT} 이라 <b>탈퇴가 실제로 확정된 뒤에만</b> 돈다 — 롤백된 탈퇴에서는 호출조차
 * 되지 않는다(계정은 살아남았는데 사진만 사라지는 상태를 원천적으로 막는다). 실패해도 탈퇴 응답은
 * 204 그대로이고, 실패한 EP 는 {@link ProfileImageEraser} 가 ERROR 로 남긴다.
 *
 * <p>탈퇴 로직이 이 클래스를 직접 부르지 않는 이유는 방향이다 — 탈퇴는 "무슨 일이 있었는지"만 알리고,
 * 그 결과 무엇을 지우는지는 이미지 쪽이 안다. 나중에 다른 뒷정리가 붙어도 탈퇴 코드는 그대로다.
 */
@Component
@RequiredArgsConstructor
public class WithdrawnProfileImageListener {

    private final ProfileImageEraser eraser;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void eraseProfileImage(UserWithdrawnEvent event) {
        eraser.eraseQuietly(event.profileImgUrl());
    }
}
