package com.skhynix.user.oauth.service;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserOauthLinkRepository;
import com.skhynix.user.oauth.policy.SocialAccountPolicy;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "이 이메일은 <b>소셜로만</b> 가입돼 비밀번호 로그인이 성립하지 않는 계정인가"만 판정한다.
 *
 * <p>자체 가입의 인증번호 발송이 409 를 내기 직전에 불린다. 그 판정에는 연동 테이블과 소셜 전용
 * 비밀번호 규칙이 함께 필요한데 둘 다 소셜 로그인 기능의 지식이라, {@code auth} 쪽으로 옮겨 복제하지
 * 않고 여기 두고 물어보게 했다.
 */
@Service
@RequiredArgsConstructor
public class SocialOnlyAccountInspector {

    private final UserAccountRepository userAccountRepository;
    private final UserOauthLinkRepository userOauthLinkRepository;

    /**
     * 그 계정에 들어갈 수 있는 provider 이름들 — 경로 변수와 같은 <b>소문자</b>다(클라이언트가 그대로
     * 자기 로그인 버튼에 대응시킬 수 있어야 한다).
     *
     * <p>비어 있으면 "소셜 전용 계정이 아니다"라는 뜻이고, 호출자는 그때 기존 응답을 그대로 내야 한다.
     * 자체 가입 뒤 소셜을 <b>추가로</b> 연동한 계정이 여기 걸리면 안 된다 — 그 계정은 비밀번호가 살아
     * 있어 안내해야 할 말이 "소셜로 로그인하라"가 아니라 기존의 "이미 사용 중"이다.
     *
     * <p>연동이 하나도 없는 소셜 전용 계정은 이론상 나오지 않지만(선점 해제는 새 연동 생성과 한
     * 트랜잭션이다), 그런 값이 오면 빈 목록이 되어 자연히 기존 응답으로 접힌다.
     */
    @Transactional(readOnly = true)
    public List<String> loginableProviders(String email) {
        // ⚠ 탈퇴 계정은 제외다. 이메일은 30일간 점유돼 있지만 그 소셜로 로그인해도 같은 409 라,
        //   안내하면 사용자를 제자리로 돌아오는 길로 보내게 된다.
        return userAccountRepository.findByUser_EmailAndExitAtIsNull(email)
                .filter(account -> SocialAccountPolicy.LOCKED_PASSWORD.equals(account.getPassword()))
                .map(account -> userOauthLinkRepository.findAllByUserAccount_Id(account.getId()).stream()
                        .map(link -> link.getProvider().name().toLowerCase(Locale.ROOT))
                        .sorted()
                        .toList())
                .orElseGet(List::of);
    }
}
