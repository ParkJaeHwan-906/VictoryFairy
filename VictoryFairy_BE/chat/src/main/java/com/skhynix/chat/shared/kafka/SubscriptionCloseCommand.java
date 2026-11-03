package com.skhynix.chat.shared.kafka;

/**
 * 다른 파드에 있는 구독을 닫으라는 명령. key = targetUserAccountId.
 *
 * <p>의미는 quiz 의 같은 이름 명령과 같다: {@code originInstanceId} 가 자기 인스턴스면 무시(방금 연 새 구독이 자기
 * 축출 명령에 끊기지 않게), {@code allRooms=true} 면 그 사용자의 전 구독, {@code false} 면 {@code gameId} 방만.
 *
 * @param gameId {@code allRooms=false} 일 때만 의미가 있다(축출이면 null)
 */
public record SubscriptionCloseCommand(
        String type,
        Long targetUserAccountId,
        String originInstanceId,
        boolean allRooms,
        String gameId) implements ChatControlMessage {

    public static final String TYPE = "subscription-close";

    /** 새 구독 성립 시 같은 사용자의 기존 구독을 방 불문 전부 닫는다(last-one-wins). */
    public static SubscriptionCloseCommand evict(Long targetUserAccountId, String originInstanceId) {
        return new SubscriptionCloseCommand(TYPE, targetUserAccountId, originInstanceId, true, null);
    }

    /** 명시적 퇴장. 그 방의 구독만 닫는다. */
    public static SubscriptionCloseCommand leave(Long targetUserAccountId, String originInstanceId, String gameId) {
        return new SubscriptionCloseCommand(TYPE, targetUserAccountId, originInstanceId, false, gameId);
    }

    @Override
    public String kafkaKey() {
        return String.valueOf(targetUserAccountId);
    }
}
