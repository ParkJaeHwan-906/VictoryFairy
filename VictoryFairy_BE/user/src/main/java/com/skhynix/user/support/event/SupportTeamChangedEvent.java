package com.skhynix.user.support.event;

/**
 * 응원 구단이 <b>다른 구단으로</b> 바뀌었음을 알리는 이벤트. 첫 선택·같은 구단 재선택에서는 내지 않는다.
 *
 * <p>Redis 발행을 {@code SupportService} 트랜잭션 밖으로 빼기 위한 경계다. 그 트랜잭션은 계정 행 배타 락을 쥐므로
 * 안에서 Redis 를 기다리면 같은 계정의 로그인까지 묶이고, 커밋 전에 발행하면 chat 이 옛 값을 다시 읽어 캐시한다.
 *
 * @param userAccountId {@code user_account.id}(내부 PK) — chat 캐시의 키가 이 값이라 uid 가 아니다
 */
public record SupportTeamChangedEvent(Long userAccountId) {
}
