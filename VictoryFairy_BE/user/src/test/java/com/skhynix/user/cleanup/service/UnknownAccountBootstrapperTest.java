package com.skhynix.user.cleanup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link UnknownAccountBootstrapper} 단위 테스트 — find-or-create 멱등성(USER-EDC-45)과 더미 계정
 * 예약값(USER-EDC-30~33)이 실제로 {@code UserAccount.reserved(...)}에 실리는지를 검증한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 */
@ExtendWith(MockitoExtension.class)
class UnknownAccountBootstrapperTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    private UnknownAccountBootstrapper bootstrapper;

    @BeforeEach
    void setUp() {
        bootstrapper = new UnknownAccountBootstrapper(userRepository, userAccountRepository);
    }

    private User existingUser() {
        return User.builder().name("UNKNOWN").tel(UnknownAccountPolicy.TEL)
                .email(UnknownAccountPolicy.EMAIL).gender(UnknownAccountPolicy.GENDER).build();
    }

    @Test
    @DisplayName("[USER-EDC-45] 더미 계정이 이미 있으면(find-or-create의 find) users·users_account 어느 "
            + "쪽도 저장하지 않는다 — 반복 기동해도 2행이 되지 않는 멱등성의 근거")
    void run_accountAlreadyExists_doesNotCreateAnything() {
        // given
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID))
                .willReturn(Optional.of(UserAccount.reserved(UnknownAccountPolicy.UID, existingUser(),
                        UnknownAccountPolicy.NICKNAME, UnknownAccountPolicy.LOCKED_PASSWORD)));

        // when
        bootstrapper.run(null);

        // then
        verify(userRepository, never()).save(any());
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("계정 행만 없고 users 행은 이미 있으면(부분 정리 등) users는 재사용하고 users_account만 새로 만든다")
    void run_accountMissingButUserExists_reusesUser_createsOnlyAccount() {
        // given
        User existing = existingUser();
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID)).willReturn(Optional.empty());
        given(userRepository.findByEmail(UnknownAccountPolicy.EMAIL)).willReturn(Optional.of(existing));

        // when
        bootstrapper.run(null);

        // then
        verify(userRepository, never()).save(any());
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(existing);
    }

    @Test
    @DisplayName("[USER-EDC-30, USER-EDC-31, USER-EDC-32, USER-EDC-33] 아무것도 없으면 users를 먼저 만들고"
            + " 그다음 UnknownAccountPolicy 예약값(uid·닉네임·잠긴 비밀번호)으로 users_account를 만든다")
    void run_nothingExists_createsUserThenAccount_withReservedValues() {
        // given
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID)).willReturn(Optional.empty());
        given(userRepository.findByEmail(UnknownAccountPolicy.EMAIL)).willReturn(Optional.empty());
        User savedUser = existingUser();
        given(userRepository.save(any())).willReturn(savedUser);

        // when
        bootstrapper.run(null);

        // then: users가 users_account보다 먼저 저장된다(email UNIQUE 충돌 회피가 이 순서에 달려 있다)
        InOrder inOrder = inOrder(userRepository, userAccountRepository);
        inOrder.verify(userRepository).save(any());
        inOrder.verify(userAccountRepository).save(any());

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(captor.capture());
        UserAccount created = captor.getValue();
        assertThat(created.getUid()).isEqualTo(UnknownAccountPolicy.UID);
        assertThat(created.getNickname()).isEqualTo(UnknownAccountPolicy.NICKNAME);
        assertThat(created.getPassword()).isEqualTo(UnknownAccountPolicy.LOCKED_PASSWORD);
        // BCrypt 패턴($2a$/$2b$ 등)이 아니어야 matches()가 예외 없이 항상 false를 낸다(로그인 불가 근거)
        assertThat(created.getPassword()).doesNotStartWith("$2");
        assertThat(created.isWithdrawn()).isFalse();
    }
}
