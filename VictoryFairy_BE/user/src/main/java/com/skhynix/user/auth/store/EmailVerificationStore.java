package com.skhynix.user.auth.store;

import java.util.Optional;

/**
 * 이메일 인증 상태 저장소(포트). 인증번호·시도횟수·쿨다운·인증완료 상태를 전부 TTL 기반으로 보관한다.
 * 구현체는 Redis({@link RedisEmailVerificationStore})를 사용하되, 서비스는 이 인터페이스에만 의존한다
 * (실제 Redis 연산을 서비스에 인라인하지 않는다).
 *
 * <p><b>키 네이밍 규칙(구현이 지켜야 할 계약)</b> — 모두 접두사 {@code email:verify:} 사용:
 * <ul>
 *   <li>인증번호     : {@code email:verify:code:{email}}     · 값=6자리 숫자 · TTL 5분</li>
 *   <li>시도 횟수    : {@code email:verify:attempts:{email}} · 값=정수 카운터 · 코드와 함께 만료(TTL 5분)</li>
 *   <li>쿨다운 마커  : {@code email:verify:cooldown:{email}} · 값 무의미(존재 여부만) · TTL 60초</li>
 *   <li>인증완료 상태: {@code email:verify:verified:{email}} · 값 무의미(존재 여부만) · TTL 30분</li>
 * </ul>
 *
 * <p>형식 검증(400)·정책 판정(시도 5회 초과 등)은 호출하는 서비스가 하고, 이 저장소는 순수 저장/조회만
 * 책임진다. TTL 상수(5분/60초/30분)와 시도 한도(5회)의 "의미"는 서비스가 알고, 저장소는 "값을 어느 키에
 * 얼마의 TTL로 넣고 빼는가"만 안다.
 */
public interface EmailVerificationStore {

    /**
     * 인증번호를 {@code email:verify:code:{email}} 키에 TTL 5분으로 저장한다. 같은 키가 있으면 덮어써
     * 이전 코드를 무효화한다(재발송 시나리오).
     */
    void saveCode(String email, String code);

    /**
     * {@code email:verify:code:{email}} 키의 인증번호를 조회한다. 없거나 만료됐으면 빈 값.
     */
    Optional<String> findCode(String email);

    /**
     * 인증번호와 시도 카운터를 함께 삭제한다({@code code:{email}} + {@code attempts:{email}}).
     * 검증 성공(1회용 소비)·시도 초과 차단·재발송 시 이전 상태 무효화에 쓰인다.
     */
    void invalidateCode(String email);

    /**
     * {@code email:verify:attempts:{email}} 카운터를 1 증가시키고 증가 후 값을 반환한다. 최초 증가 시
     * 코드와 동일한 TTL(5분)을 설정해 코드와 함께 만료되게 한다.
     */
    int incrementAttempts(String email);

    /**
     * {@code email:verify:attempts:{email}} 현재 시도 횟수를 조회한다. 키가 없으면 0.
     */
    int getAttempts(String email);

    /**
     * {@code email:verify:cooldown:{email}} 쿨다운 마커를 TTL 60초로 설정한다(재발송 스팸 방어).
     */
    void startCooldown(String email);

    /**
     * {@code email:verify:cooldown:{email}} 쿨다운 마커가 살아 있는지(재발송 불가 상태인지) 조회한다.
     */
    boolean isCoolingDown(String email);

    /**
     * {@code email:verify:verified:{email}} 인증완료 상태를 TTL 30분으로 저장한다(검증 성공 시).
     */
    void markVerified(String email);

    /**
     * {@code email:verify:verified:{email}} 인증완료 상태가 살아 있는지 조회한다(signup 선행 조건).
     * 키 부재(미인증·만료)는 동일하게 false.
     */
    boolean isVerified(String email);

    /**
     * {@code email:verify:verified:{email}} 인증완료 상태를 삭제한다(가입 성공 시 1회용 소비).
     */
    void consumeVerified(String email);
}
