package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.gateway.service.GatewayConsumer;
import com.skhynix.chat.gateway.service.RoomBatcher;
import com.skhynix.chat.history.service.HistoryStreamWriter;
import com.skhynix.chat.history.service.HistoryWriterListener;
import com.skhynix.chat.message.controller.ChatMessageController;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.room.controller.ChatRoomController;
import com.skhynix.chat.room.service.RoomLifecycleScheduler;
import com.skhynix.chat.subscription.controller.ChatSubscriptionController;
import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 세 역할 플래그(API·게이트웨이·history-writer)의 조합별로 앱 전체를 실제로 띄워 빈·엔드포인트·브로커 그룹을 확인한다.
 * 스프링 테스트 캐시 밖에서 직접 띄우고 닫는다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChatRoleFlagsIT {

    private static Set<String> consumerGroups() throws Exception {
        try (var admin = Containers.admin()) {
            return admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
                    .map(g -> g.groupId()).collect(Collectors.toSet());
        }
    }

    private static boolean threadExists(String namePrefix) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().startsWith(namePrefix) && t.isAlive());
    }

    @Test
    @DisplayName("[CHAT-GC-3] chat.role.api=false 로 기동하면 인증된 GET /chat/rooms 도 404 이고 /rooms/** 컨트롤러·방 수명 스케줄러 빈이 없으며 GET /chat/actuator/health 는 200 이다")
    void apiRoleOff_noRestHandlersNoScheduler_healthStillUp() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        try (AppLauncher app = new AppLauncher()) {
            Game todayGame = com.skhynix.chat.support.ChatFixtures.game("ROLE-OFF-GAME",
                    new com.skhynix.chat.shared.ChatClock().today().atTime(18, 30), "SCHEDULED");
            org.mockito.BDDMockito.given(app.gameRepository
                    .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .willReturn(List.of(todayGame));
            String token = app.userToken(7001);
            app.start("--chat.role.api=false", "--chat.role.history-writer=false");

            E2eHttp http = app.http();

            assertThat(http.get("/chat/rooms", token).status()).isEqualTo(404);
            assertThat(http.get("/chat/rooms/ROLE-OFF-GAME", token).status()).isEqualTo(404);
            assertThat(http.post("/chat/rooms/ROLE-OFF-GAME/messages", token, "{}").status()).isEqualTo(404);
            assertThat(http.get("/chat/rooms", null).status()).as("미인증은 401").isEqualTo(401);
            assertThat(http.get("/chat/actuator/health", null).status()).isEqualTo(200);
            for (Class<?> type : List.of(ChatRoomController.class, ChatMessageController.class,
                    ChatSubscriptionController.class, RoomLifecycleScheduler.class)) {
                assertThat(app.context.getBeansOfType(type)).as(type.getSimpleName()).isEmpty();
            }
            // 기동 시 1회 실행(CHAT-GC-19)도 API 역할 전용이다 — 오늘 경기가 있어도 방 키가 만들어지지 않는다
            Thread.sleep(2000);
            try (var redis = new RedisProbe()) {
                assertThat(redis.template.hasKey("chat:room:ROLE-OFF-GAME")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("[CHAT-GC-4] API·게이트웨이를 끄고 history-writer 만 켜면 게이트웨이 빈·스레드가 없고, 브로커에는 chat-history-writer 그룹만 이 파드의 멤버를 가진다(2, 96)")
    void historyWriterOnly_noGatewayButGroupMember() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        try (AppLauncher app = new AppLauncher()) {
            app.start("--chat.role.api=false", "--chat.role.gateway=false");

            for (Class<?> type : List.of(GatewayConsumer.class, RoomBatcher.class, SseEmitterRegistry.class)) {
                assertThat(app.context.getBeansOfType(type)).as(type.getSimpleName()).isEmpty();
            }

            assertThat(app.context.getBeansOfType(HistoryWriterListener.class)).hasSize(1);
            assertThat(app.context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("chat-history-writer"))
                    .isNotNull();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(consumerGroups()).contains("chat-history-writer");
                assertThat(consumerGroups()).allMatch("chat-history-writer"::equals);
            });
        }
    }

    @Test
    @DisplayName("[CHAT-GC-5][CHAT-GC-102] history-writer 를 끄면 Stream 쓰기 빈과 chat-history-writer 리스너 컨테이너가 없고, 전송 202·실시간 SSE 수신은 정상이다")
    void historyWriterOff_noWriterBeans_sendAndRealtimeStillWork() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        try (AppLauncher app = new AppLauncher()) {
            String room = app.room();
            String sender = app.userToken(7101);
            String listener = app.userToken(7102);
            app.start("--chat.role.history-writer=false");
            E2eHttp http = app.http();

            assertThat(app.context.getBeansOfType(HistoryStreamWriter.class)).isEmpty();
            assertThat(app.context.getBeansOfType(HistoryWriterListener.class)).isEmpty();
            assertThat(app.context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("chat-history-writer"))
                    .isNull();

            try (E2eHttp.SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", listener, null)) {
                SseEmitterRegistry registry = app.context.getBean(SseEmitterRegistry.class);
                await().atMost(Duration.ofSeconds(15)).until(() -> registry.count(room) == 1);
                // 게이트웨이 컨슈머가 assign 을 마칠 때까지 기동 직후의 메시지는 latest 정책상 놓칠 수 있다 — 준비될 때까지 보낸다
                await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                    String body = "{\"content\":\"역할 테스트\",\"clientMsgId\":\"" + java.util.UUID.randomUUID() + "\"}";
                    E2eHttp.Resp resp = http.post("/chat/rooms/" + room + "/messages", sender, body);
                    assertThat(resp.status()).isEqualTo(202);
                    return stream.dataEvents().stream().anyMatch(e -> "messages".equals(e.name()));
                });
            }
        }
    }

    @Test
    @DisplayName("[CHAT-GC-6] API 역할을 켜고 게이트웨이 역할을 끈 채 앱을 띄우면 기동이 거부되고 사유에 '게이트웨이 역할이 필요하다' 가 남는다")
    void apiOnGatewayOff_failsToStartWithReason() throws Exception {
        Containers.createChatTopics(2);
        AppLauncher app = new AppLauncher();

        assertThatThrownBy(() -> app.start("--chat.role.gateway=false"))
                .satisfies(e -> {
                    StringBuilder chain = new StringBuilder();
                    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                        chain.append(t.getMessage()).append(" | ");
                    }
                    assertThat(chain.toString()).contains("게이트웨이 역할이 필요하다");
                });
    }

    @Test
    @DisplayName("[CHAT-GC-2] 역할 설정 없이 띄운 파드 하나에서 REST 핸들러·assign 컨슈머 스레드·컨슈머 그룹 가입이 전부 관측된다")
    void defaultRoles_everythingRuns() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        try (AppLauncher app = new AppLauncher()) {
            String token = app.userToken(7201);
            app.start();

            assertThat(app.http().get("/chat/rooms", token).status()).isEqualTo(200);
            assertThat(app.context.getBeansOfType(GatewayConsumer.class)).hasSize(1);

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(consumerGroups()).contains("chat-history-writer"));
        }
    }

    /** Redis 를 직접 들여다보는 최소 프로브. */
    private static final class RedisProbe implements AutoCloseable {
        final org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                                Containers.redisHost(), Containers.redisPort()));
        final org.springframework.data.redis.core.StringRedisTemplate template;

        RedisProbe() {
            factory.afterPropertiesSet();
            template = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            template.afterPropertiesSet();
        }

        @Override
        public void close() {
            factory.destroy();
        }
    }
}
