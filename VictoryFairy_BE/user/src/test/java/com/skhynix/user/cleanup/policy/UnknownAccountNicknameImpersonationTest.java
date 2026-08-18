package com.skhynix.user.cleanup.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.user.auth.policy.NicknamePolicy;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * USER-EDC-35 회귀 테스트 — 더미 계정 닉네임 {@code (알수없음)}을 일반 사용자가 사칭할 수 없다는 계약을
 * {@link NicknamePolicy}(정책 단일 출처) 수준에서 직접 고정한다.
 *
 * <p>이 문서가 사칭 불가의 근거로 삼는 사실 그 자체 — {@code NicknamePolicy.REGEX}의 허용 문자 집합에
 * 괄호가 없다는 것 — 이 테스트가 깨지면 사칭 방지 근거가 통째로 무너진다는 뜻이므로, 기존
 * {@code NicknamePolicyTest}의 일반 화이트리스트 테스트와 별개로 이 정확한 문자열을 못박아 둔다.
 * (신규 클래스인 이유: {@code cleanup} 패키지의 요구사항이 이 특정 문자열에 의미를 부여하고,
 * {@code NicknamePolicyTest}는 정책 일반을 다뤄 이 사칭 문맥과 성격이 다르다.)
 */
class UnknownAccountNicknameImpersonationTest {

    @Test
    @DisplayName("[USER-EDC-35] 더미 계정 닉네임 \"(알수없음)\"은 길이(6자 — 요구사항 문서의 \"5자\" 서술과"
            + " 달리 실제로는 괄호 2개를 포함해 6자다, 1~10 범위는 여전히 통과)는 통과하지만 괄호가 허용 "
            + "문자에 없어 문자 구성 위반으로 거절된다")
    void findViolation_unknownAccountNickname_rejectedByPatternRule() {
        // given
        String nickname = UnknownAccountPolicy.NICKNAME;
        assertThat(nickname).hasSize(6);

        // when
        Optional<String> violation = NicknamePolicy.findViolation(nickname);

        // then
        assertThat(violation).contains(NicknamePolicy.PATTERN_MESSAGE);
    }
}
