package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;

/**
 * 요청 인증에 필요한 활성 계정의 최소 정보 — 내부 PK와 토큰 무효화 기준 시각 두 값.
 *
 * <p>엔티티가 아니라 프로젝션인 이유: 필터는 요청마다 이 조회를 돌지만 계정의 나머지 컬럼(닉네임·
 * 비밀번호 해시 등)을 단 하나도 읽지 않는다. 반대로 {@code Long} 하나로도 담기지 않는다 — 기준 시각을
 * <b>별도 조회로</b> 가져오면 요청당 DB 조회가 하나 늘어 stateless 검증의 비용 계약이 깨진다. 그래서
 * 이미 돌던 조회의 select 절에 컬럼 하나를 더 싣는 형태가 됐다.
 *
 * <p>인터페이스 프로젝션({@code QuizLikeCountView})이 아니라 record 인 이유는 이 값이 집계 결과가
 * 아니라 한 행의 일부라 생성자로 그대로 조립되고, JPQL 생성자 표현식이 select 절 변경을 컴파일 시점에
 * 잡아 주기 때문이다(별칭 이름에 의존하지 않는다).
 */
public record ActiveAccountView(Long id, Long passwordChangedEpochSecond) {

    /**
     * 무효화 판정은 {@link UserAccount#acceptsTokenIssuedAt(Long, long)} 한 곳에만 있다 — 여기서
     * 비교식을 다시 쓰면 엔티티 쪽(refresh 재발급)과 필터 쪽 규칙이 갈라진다.
     */
    public boolean acceptsTokenIssuedAt(long issuedAtEpochSecond) {
        return UserAccount.acceptsTokenIssuedAt(passwordChangedEpochSecond, issuedAtEpochSecond);
    }
}
