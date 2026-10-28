package com.skhynix.user.game.realtime;

/**
 * 경기 갱신을 "이 앱의 모든 파드"의 구독자에게 전달하는 입구. prod는 Redis pub/sub({@link RedisGameEventPublisher}),
 * 그 밖은 로컬 레지스트리 직결({@link InMemoryGameEventPublisher}).
 */
public interface GameEventPublisher {

    void publish(GameUpdateEvent event);
}
