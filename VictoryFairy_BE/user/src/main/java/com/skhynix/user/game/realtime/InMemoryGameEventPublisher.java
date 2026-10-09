package com.skhynix.user.game.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 단일 프로세스(로컬·테스트)용 — Redis 없이 자기 레지스트리로 바로 넘긴다. */
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class InMemoryGameEventPublisher implements GameEventPublisher {

    private final GameSseRegistry registry;

    @Override
    public void publish(GameUpdateEvent event) {
        registry.publish(event);
    }
}
