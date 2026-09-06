package com.skhynix.domain.user.entity;

/**
 * 소셜 로그인 제공자.
 *
 * <p><b>{@code Gender}와 달리 이름 문자열로 저장한다</b>({@link UserOauthLink#getProvider()}의
 * {@code @Enumerated(STRING)}). {@code Gender}가 ORDINAL 인 것은 이미 값이 들어 있는 컬럼이라
 * 바꿀 수 없어서지, 그쪽이 더 나아서가 아니다 — 이 열거는 테이블 자체가 신규라 순서 의존을
 * 물려받을 이유가 없다.
 *
 * <p>이름 저장을 택한 이유는 운영 대응이다. 연동 사고 조사는 거의 전부 "이 행이 어느 provider
 * 냐"를 DB·로그에서 눈으로 읽는 일인데, ORDINAL 이면 {@code 0/1/2}가 무엇인지 알려면 이 파일을
 * 열어야 하고 <b>선언 순서를 잘못 건드린 사고는 조용히 남의 계정으로 로그인시키는 형태로</b>
 * 드러난다(연동 행은 신원 그 자체라 값이 어긋나면 곧바로 오인증이다).
 *
 * <p>그래서 여기서는 선언 순서를 바꿔도 안전하다. 대신 <b>상수 이름은 바꾸지 말 것</b> — 저장된
 * 문자열이 곧 값이다.
 */
public enum OauthProvider {
    KAKAO,
    NAVER,
    GOOGLE
}
