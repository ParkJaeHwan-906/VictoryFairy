package com.skhynix.user.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserOauthLink;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserOauthLinkRepository;
import com.skhynix.user.oauth.policy.SocialAccountPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SocialOnlyAccountInspector} — 자체 가입의 409 를 "소셜로 로그인하라"로 세분화해도 되는지의
 * 유일한 판정자. 잘못 참을 내면 <b>비밀번호가 살아 있는 계정</b>의 주인에게 엉뚱한 안내를 하게 되고,
 * 잘못 거짓을 내면 소셜 전용 사용자가 다시 막다른 길로 돌아간다.
 */
@ExtendWith(MockitoExtension.class)
class SocialOnlyAccountInspectorTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserOauthLinkRepository userOauthLinkRepository;

    @InjectMocks
    private SocialOnlyAccountInspector inspector;

    private UserAccount accountWithPassword(String password) {
        User user = User.builder()
                .name("홍길동").tel("01012345678").email(EMAIL).gender(Gender.MALE).build();
        return UserAccount.builder().user(user).nickname("userA").password(password).build();
    }

    private UserOauthLink linkOf(UserAccount account, OauthProvider provider) {
        return UserOauthLink.builder()
                .userAccount(account).provider(provider).providerUserId("pid-" + provider).build();
    }

    @Test
    @DisplayName("소셜 전용 계정이면 연동된 provider 이름을 경로 변수와 같은 소문자로 돌려준다")
    void loginableProviders_socialOnlyAccount_returnsLowercaseProviderNames() {
        // given
        UserAccount account = accountWithPassword(SocialAccountPolicy.LOCKED_PASSWORD);
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(EMAIL))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.findAllByUserAccount_Id(account.getId()))
                .willReturn(List.of(linkOf(account, OauthProvider.NAVER),
                        linkOf(account, OauthProvider.GOOGLE)));

        // when
        List<String> providers = inspector.loginableProviders(EMAIL);

        // then: 클라이언트가 자기 버튼 id 에 그대로 대응시킬 수 있어야 한다
        assertThat(providers).containsExactly("google", "naver");
    }

    @Test
    @DisplayName("자체 가입 뒤 소셜을 추가로 연동한 계정은 비밀번호가 살아 있으므로 안내 대상이 아니다"
            + "(연동이 있다는 사실만으로 판정하면 이 계정이 잘못 걸린다)")
    void loginableProviders_selfSignupAccountWithLink_returnsEmpty() {
        // given
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(EMAIL))
                .willReturn(Optional.of(accountWithPassword("$2a$10$realBcryptHash")));

        // when
        List<String> providers = inspector.loginableProviders(EMAIL);

        // then: 연동 테이블은 보지도 않는다
        assertThat(providers).isEmpty();
        verifyNoInteractions(userOauthLinkRepository);
    }

    @Test
    @DisplayName("탈퇴 계정이 점유한 이메일은 안내 대상이 아니다"
            + "(그 소셜로 로그인해도 같은 409 라 제자리로 돌아온다)")
    void loginableProviders_withdrawnAccount_returnsEmpty() {
        // given: 활성 조회라 탈퇴 계정은 애초에 잡히지 않는다
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(EMAIL)).willReturn(Optional.empty());

        // when & then
        assertThat(inspector.loginableProviders(EMAIL)).isEmpty();
        verifyNoInteractions(userOauthLinkRepository);
    }

    @Test
    @DisplayName("연동이 하나도 없는 소셜 전용 계정은 빈 목록이 되어 기존 응답으로 접힌다"
            + "(안내할 provider 가 없는데 소셜로 로그인하라고 말하지 않는다)")
    void loginableProviders_socialOnlyAccountWithoutLinks_returnsEmpty() {
        // given
        UserAccount account = accountWithPassword(SocialAccountPolicy.LOCKED_PASSWORD);
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull(EMAIL))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.findAllByUserAccount_Id(account.getId())).willReturn(List.of());

        // when & then
        assertThat(inspector.loginableProviders(EMAIL)).isEmpty();
    }
}
