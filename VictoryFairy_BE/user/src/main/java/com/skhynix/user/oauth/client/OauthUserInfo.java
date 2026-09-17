package com.skhynix.user.oauth.client;

/**
 * provider 사용자 정보에서 <b>신원 해석에 실제로 쓰는 것만</b> 추린 값.
 *
 * <p>닉네임·프로필 이미지를 담지 않는 것은 빠뜨린 게 아니다. provider 닉네임을 그대로 저장하면
 * {@code (알수없음)} 예약 계정 사칭을 막는 근거({@code NicknamePolicy} 의 문자 화이트리스트, 괄호·공백·
 * 이모지 불가)를 통째로 우회한다 — {@code users_account.nickname} 에는 DB UNIQUE 가 없어 막는 주체가
 * 그 문자 정책뿐이다. 소셜 가입 닉네임은 언제나 사용자 입력이다.
 *
 * @param providerUserId provider 가 준 식별자 <b>그대로</b>(해싱·접두 부착 금지 — 저장 형태가 바뀌면
 *                       기존 연동 행이 전부 남남이 된다)
 * @param email          쓸 수 있는 이메일이 없으면 {@code null}. 카카오는 비즈 앱 전환 전까지 항상 이쪽이다
 * @param emailVerified  provider 의 검증 판정. {@code email} 이 {@code null} 이면 의미 없다
 */
public record OauthUserInfo(String providerUserId, String email, boolean emailVerified) {

    /**
     * 이번 응답에 쓸 수 있는 이메일이 있는가.
     *
     * <p>⚠ 호출자는 <b>provider 종류로 분기하지 말고 이 판정으로 분기해야 한다.</b> 카카오 비즈 앱
     * 전환이 승인되면 이메일이 오기 시작하는데, {@code if (provider == KAKAO)} 로 짜 두면 그날 코드를
     * 고쳐야 하고 고치기 전까지는 이메일이 와도 입력 단계를 계속 태운다.
     */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
