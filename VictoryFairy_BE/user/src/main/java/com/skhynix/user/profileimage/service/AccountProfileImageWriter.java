package com.skhynix.user.profileimage.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 이미지 갱신의 <b>트랜잭션 단위</b>. 지휘하는 쪽({@link AccountProfileImageService})은
 * 트랜잭션을 열지 않고 여기 한 메서드만 연다.
 *
 * <p>클래스를 나눈 이유는 {@code ExpiredAccountEraser} 와 같다 — <b>프록시</b>다. 같은 빈 안에서
 * 부르면 {@code @Transactional} 이 걸리지 않아 변경이 flush 되지 않는다. 나아가 여기서는 분리가
 * 성능 계약이기도 하다: S3 업로드(외부 호출)를 트랜잭션 밖에 두어야 계정 행을 참조하는 쓰기들이
 * 네트워크 대기에 묶이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AccountProfileImageWriter {

    private final UserAccountRepository userAccountRepository;

    /**
     * 컬럼을 새 EP 로 교체하고 <b>직전 값</b>을 돌려준다.
     *
     * <p>⚠ 반환값이 핵심이다. {@code changeProfileImgUrl()} 은 직전 EP 를 잃어버리므로, 옛 객체를
     * 지우려면 전이 <b>전에</b> 값을 챙겨 두는 이 자리 말고는 알아낼 방법이 없다.
     *
     * <p>계정 행을 잠그지 않는다(일반 {@code findById}). 여기서 지키는 불변식이 없어서다 — 같은
     * 계정이 동시에 두 번 업로드하면 마지막 EP 가 이기고 진 쪽 객체는 참조 없이 남는다.
     *
     * @return 직전 EP. 이미지가 없던 계정이면 {@code null}
     */
    @Transactional
    public String replace(Long userAccountId, String newEndpoint) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증
        // 근거가 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다.
        UserAccount account = userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        String previousEndpoint = account.getProfileImgUrl();
        account.changeProfileImgUrl(newEndpoint);
        return previousEndpoint;
    }
}
