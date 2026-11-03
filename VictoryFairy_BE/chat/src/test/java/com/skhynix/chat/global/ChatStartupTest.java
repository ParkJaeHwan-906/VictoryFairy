package com.skhynix.chat.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skhynix.chat.gateway.service.GatewayConsumer;
import com.skhynix.chat.gateway.service.RoomBatcher;
import com.skhynix.chat.global.config.ChatApiRoleConfig;
import com.skhynix.chat.global.config.ChatCoreConfig;
import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.history.config.HistoryWriterConfig;
import com.skhynix.chat.history.service.HistoryStreamWriter;
import com.skhynix.chat.history.service.HistoryWriterListener;
import com.skhynix.chat.message.controller.ChatMessageController;
import com.skhynix.chat.message.service.ChatHistoryService;
import com.skhynix.chat.message.service.ChatMessageSendService;
import com.skhynix.chat.message.service.ChatReportService;
import com.skhynix.chat.message.service.MessageDedupStore;
import com.skhynix.chat.message.service.SendRateLimiter;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.room.controller.ChatRoomController;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.room.service.ChatRoomService;
import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.chat.room.service.RoomLifecycleScheduler;
import com.skhynix.chat.subscription.controller.ChatSubscriptionController;
import com.skhynix.chat.subscription.service.ChatSubscriptionService;
import com.skhynix.profanity.ProfanityDetector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * 기동 거부·설정 키·역할 플래그. 실제 application.yaml(+프로필)을 읽는 SpringApplication 으로 확인한다.
 * Kafka/Redis/DB 에 연결하지 않는 최소 구성이다.
 */
class ChatStartupTest {

    /** 로컬 .env 가 설정을 새로 흘려 넣지 못하게 config import 를 비운다(.env 가 원격 DB 를 가리킨다). */
    private static final String NO_ENV_FILE = "--spring.config.import=optional:classpath:/does-not-exist.properties";
    private static final String NO_WEB = "--spring.main.web-application-type=none";

    @Configuration
    @Import({ChatCoreConfig.class, org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class})
    static class CoreOnly {
    }

    @Configuration
    @Import({ChatCoreConfig.class, ChatApiRoleConfig.class,
            org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class})
    static class CoreAndApiRole {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private ConfigurableApplicationContext run(Class<?> config, Map<String, String> environmentVariables, String... args) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        // 실제 OS 환경변수 대신 주어진 값만 환경변수로 본다(개발자 머신의 KAFKA_BOOTSTRAP_SERVERS 등에 영향받지 않게)
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new java.util.HashMap<>(environmentVariables)));
        String[] allArgs = new String[args.length + 2];
        allArgs[0] = NO_ENV_FILE;
        allArgs[1] = NO_WEB;
        System.arraycopy(args, 0, allArgs, 2, args.length);
        return new SpringApplicationBuilder(config)
                .web(WebApplicationType.NONE)
                .environment(environment)
                .logStartupInfo(false)
                .run(allArgs);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    // ---------- 설정 키 기본값·환경변수 덮어쓰기 (2, 11) ----------

    @Test
    @DisplayName("[CHAT-GC-11] 설정 없이 기동하면 설정 키 표의 기본값이 적용되고, 세 역할은 모두 켜져 있다(CHAT-GC-2)")
    void defaults_matchSpecTable() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            ChatProperties p = ctx.getBean(ChatProperties.class);

            assertThat(p.role()).isEqualTo(new ChatProperties.Role(true, true, true));
            assertThat(p.history().maxLen()).isEqualTo(50_000);
            assertThat(p.recovery()).isEqualTo(new ChatProperties.Recovery(500, 5));
            assertThat(p.gateway()).isEqualTo(new ChatProperties.Gateway(150, 1000, 0));
            assertThat(p.rateLimit().perSecond()).isEqualTo(3);
            assertThat(p.dedup().ttlSeconds()).isEqualTo(120);
            assertThat(p.kafka().sendTimeoutMs()).isEqualTo(3000);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-11] 모든 설정 키는 환경변수(CHAT_RATE_LIMIT_PER_SECOND 등 relaxed binding)로 덮어쓸 수 있다")
    void environmentVariables_overrideEveryKey() {
        Map<String, String> env = Map.ofEntries(
                Map.entry("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
                Map.entry("CHAT_RATE_LIMIT_PER_SECOND", "5"),
                Map.entry("CHAT_ROLE_HISTORY_WRITER", "false"),
                Map.entry("CHAT_HISTORY_MAX_LEN", "1000"),
                Map.entry("CHAT_RECOVERY_BATCH_SIZE", "10"),
                Map.entry("CHAT_RECOVERY_MAX_BATCHES", "2"),
                Map.entry("CHAT_GATEWAY_BATCH_INTERVAL_MS", "50"),
                Map.entry("CHAT_GATEWAY_WRITE_TIMEOUT_MS", "700"),
                Map.entry("CHAT_GATEWAY_SAMPLING_THRESHOLD_PER_SEC", "9"),
                Map.entry("CHAT_DEDUP_TTL_SECONDS", "30"),
                Map.entry("CHAT_KAFKA_SEND_TIMEOUT_MS", "1500"));
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, env)) {
            ChatProperties p = ctx.getBean(ChatProperties.class);

            assertThat(p.rateLimit().perSecond()).isEqualTo(5);
            assertThat(p.role().historyWriter()).isFalse();
            assertThat(p.role().api()).isTrue();
            assertThat(p.history().maxLen()).isEqualTo(1000);
            assertThat(p.recovery()).isEqualTo(new ChatProperties.Recovery(10, 2));
            assertThat(p.gateway()).isEqualTo(new ChatProperties.Gateway(50, 700, 9));
            assertThat(p.dedup().ttlSeconds()).isEqualTo(30);
            assertThat(p.kafka().sendTimeoutMs()).isEqualTo(1500);
        }
    }

    // ---------- 역할 조합 (6) ----------

    @Test
    @DisplayName("[CHAT-GC-6] API 역할을 켜고 게이트웨이 역할을 끄면 기동이 거부되고 사유에 '게이트웨이 역할이 필요하다' 가 남는다")
    void apiOnWithGatewayOff_failsToStart() {
        assertThatThrownBy(() -> run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                "CHAT_ROLE_GATEWAY", "false")))
                .satisfies(e -> assertThat(allMessages(e)).contains("게이트웨이 역할이 필요하다"));
    }

    @Test
    @DisplayName("[CHAT-GC-6] 플래그를 프로퍼티(chat.role.gateway=false)로 줘도 같은 사유로 거부된다")
    void apiOnWithGatewayOff_viaProperty_failsToStart() {
        assertThatThrownBy(() -> run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"),
                "--chat.role.gateway=false"))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("chat.role.gateway"));
    }

    @Test
    @DisplayName("[CHAT-GC-2] 허용되는 역할 조합 — API+GW, GW 단독, history-writer 단독, API 와 GW 둘 다 끄기 — 은 기동된다")
    void allowedRoleCombinations_start() {
        List<List<String>> combos = List.of(
                List.of("--chat.role.history-writer=false"),
                List.of("--chat.role.api=false", "--chat.role.history-writer=false"),
                List.of("--chat.role.api=false", "--chat.role.gateway=false"),
                List.of("--chat.role.api=false", "--chat.role.gateway=false", "--chat.role.history-writer=false"));
        for (List<String> combo : combos) {
            try (ConfigurableApplicationContext ctx = run(CoreOnly.class,
                    Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"), combo.toArray(String[]::new))) {
                assertThat(ctx.getBean(ChatProperties.class)).as(combo.toString()).isNotNull();
            }
        }
    }

    // ---------- Kafka bootstrap (7) ----------

    @Test
    @DisplayName("[CHAT-GC-7] prod 프로필에서 KAFKA_BOOTSTRAP_SERVERS 가 없으면 기동이 실패한다(기본값 없음)")
    void prodProfile_withoutKafkaBootstrapServers_failsToStart() {
        assertThatThrownBy(() -> run(CoreOnly.class, Map.of(), "--spring.profiles.active=prod"))
                .satisfies(e -> assertThat(allMessages(e)).contains("KAFKA_BOOTSTRAP_SERVERS"));
    }

    @Test
    @DisplayName("[CHAT-GC-7] KAFKA_BOOTSTRAP_SERVERS 가 빈 문자열이어도 기동을 거부한다")
    void blankKafkaBootstrapServers_failsToStart() {
        assertThatThrownBy(() -> run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", ""),
                "--spring.profiles.active=prod"))
                .satisfies(e -> assertThat(allMessages(e)).contains("KAFKA_BOOTSTRAP_SERVERS"));
    }

    @Test
    @DisplayName("[CHAT-GC-7] KAFKA_BOOTSTRAP_SERVERS=10.0.0.5:9092 로 기동하면 정상이고 그 값이 spring.kafka.bootstrap-servers 가 된다")
    void kafkaBootstrapServers_fromEnvironment_starts() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class,
                Map.of("KAFKA_BOOTSTRAP_SERVERS", "10.0.0.5:9092"), "--spring.profiles.active=prod")) {
            assertThat(ctx.getEnvironment().getProperty("spring.kafka.bootstrap-servers")).isEqualTo("10.0.0.5:9092");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-7] (관찰) dev 프로필(기본 SPRING_PROFILES_ACTIVE=dev)에서는 환경변수가 없어도 localhost:29092 로 기동된다 — 요구사항의 '기본값 없음' 과 어긋나는 지점이라 보고 대상")
    void devProfile_withoutKafkaBootstrapServers_fallsBackToLocalCompose() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of())) {
            assertThat(ctx.getEnvironment().getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:29092");
        }
    }

    // ---------- 토픽·프로듀서 설정 (9, 108) ----------

    @Test
    @DisplayName("[CHAT-GC-9] 앱은 토픽을 만들지 않는다 — spring.kafka.admin.auto-create=false, 컨슈머 allow.auto.create.topics=false")
    void topicAutoCreation_isDisabled() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            assertThat(ctx.getEnvironment().getProperty("spring.kafka.admin.auto-create", Boolean.class)).isFalse();
            assertThat(ctx.getEnvironment().getProperty("spring.kafka.consumer.properties.allow.auto.create.topics",
                    Boolean.class)).isFalse();
            assertThat(ctx.getBeansOfType(org.apache.kafka.clients.admin.NewTopic.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-108] 프로듀서 설정은 acks=all, enable.idempotence=true 이고 전송 타임아웃 세 값이 send-timeout-ms 와 묶여 있다")
    void producerConfig_isIdempotentAcksAll() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
            assertThat(env.getProperty("spring.kafka.producer.properties.enable.idempotence", Boolean.class)).isTrue();
            assertThat(env.getProperty("spring.kafka.producer.properties.delivery.timeout.ms")).isEqualTo("3000");
            assertThat(env.getProperty("spring.kafka.producer.properties.request.timeout.ms")).isEqualTo("3000");
            assertThat(env.getProperty("spring.kafka.producer.properties.max.block.ms")).isEqualTo("3000");
            assertThat(env.getProperty("spring.kafka.producer.properties.linger.ms")).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-108] send-timeout-ms 를 환경변수로 바꾸면 프로듀서 타임아웃 세 값도 같이 바뀐다(어긋나면 503 뒤에 메시지가 나타난다)")
    void producerTimeouts_followSendTimeoutOverride() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class,
                Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092", "CHAT_KAFKA_SEND_TIMEOUT_MS", "1500"))) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("spring.kafka.producer.properties.delivery.timeout.ms")).isEqualTo("1500");
            assertThat(env.getProperty("spring.kafka.producer.properties.request.timeout.ms")).isEqualTo("1500");
            assertThat(env.getProperty("spring.kafka.producer.properties.max.block.ms")).isEqualTo("1500");
        }
    }

    // ---------- 그 밖의 설정 (8, 33, 25 일부) ----------

    @Test
    @DisplayName("[CHAT-GC-1] 포트 8082, context-path /chat 이고 ddl-auto 는 none, open-in-view 는 false, 비동기 요청 타임아웃은 30분이다")
    void serverAndJpaSettings() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("server.port")).isEqualTo("8082");
            assertThat(env.getProperty("server.servlet.context-path")).isEqualTo("/chat");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
            assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
            assertThat(env.getProperty("spring.mvc.async.request-timeout")).isEqualTo("30m");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-8] DB·Redis·JWT 는 quiz 와 같은 환경변수 이름(DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD, REDIS_HOST/REDIS_PORT, JWT_SECRET)으로 받는다")
    void sharedEnvironmentVariableNames() {
        Map<String, String> env = Map.of(
                "KAFKA_BOOTSTRAP_SERVERS", "kafka:9092",
                "DB_HOST", "db.example", "DB_PORT", "3307", "DB_NAME", "vf", "DB_USERNAME", "u", "DB_PASSWORD", "p",
                "REDIS_HOST", "redis.example", "REDIS_PORT", "6380", "JWT_SECRET", "shared-secret-shared-secret-shared-secret");
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, env)) {
            var e = ctx.getEnvironment();
            assertThat(e.getProperty("spring.datasource.url")).contains("db.example:3307/vf");
            assertThat(e.getProperty("spring.datasource.username")).isEqualTo("u");
            assertThat(e.getProperty("spring.datasource.password")).isEqualTo("p");
            assertThat(e.getProperty("spring.data.redis.host")).isEqualTo("redis.example");
            assertThat(e.getProperty("spring.data.redis.port")).isEqualTo("6380");
            assertThat(e.getProperty("jwt.secret")).isEqualTo("shared-secret-shared-secret-shared-secret");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-8] Redis 명령 타임아웃은 2초로 줄여 두었다 — 존재 확인이 장애 시 60초 매달리지 않고 503 이 되도록")
    void redisCommandTimeoutIsShort() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            assertThat(ctx.getEnvironment().getProperty("spring.data.redis.timeout")).isEqualTo("2s");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-10] 헬스체크는 health,metrics 만 노출한다(metrics 는 인증 필요 경로)")
    void actuatorExposure() {
        try (ConfigurableApplicationContext ctx = run(CoreOnly.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            assertThat(ctx.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health,metrics");
        }
    }

    // ---------- 역할 플래그 ↔ 빈 소속 (3, 4, 5) ----------

    private static String roleOf(Class<?> type) {
        ConditionalOnBooleanProperty conditional = type.getAnnotation(ConditionalOnBooleanProperty.class);
        assertThat(conditional).as(type.getSimpleName() + " 에 역할 조건이 있어야 한다").isNotNull();
        assertThat(conditional.matchIfMissing()).as(type.getSimpleName() + " 은 설정이 없으면 켜짐").isTrue();
        return conditional.name()[0];
    }

    @Test
    @DisplayName("[CHAT-GC-3] REST·SSE 구독·방 수명 스케줄은 전부 chat.role.api 로 켜고 끈다")
    void apiRoleOwnsRestAndScheduling() {
        for (Class<?> type : List.of(ChatRoomController.class, ChatMessageController.class,
                ChatSubscriptionController.class, ChatRoomService.class, ChatRoomGuard.class,
                ChatMessageSendService.class, ChatHistoryService.class, ChatReportService.class,
                ChatSubscriptionService.class, MessageDedupStore.class, SendRateLimiter.class,
                RoomLifecycleJob.class, RoomLifecycleScheduler.class, ChatApiRoleConfig.class)) {
            assertThat(roleOf(type)).as(type.getSimpleName()).isEqualTo("chat.role.api");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-4] 팬아웃 컨슈머·배처·쓰기 풀·레지스트리는 chat.role.gateway 로 켜고 끈다")
    void gatewayRoleOwnsFanOut() {
        for (Class<?> type : List.of(GatewayConsumer.class, RoomBatcher.class, SseFrameWriter.class,
                SseEmitterRegistry.class)) {
            assertThat(roleOf(type)).as(type.getSimpleName()).isEqualTo("chat.role.gateway");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-5] Stream·blind 집합 쓰기와 chat-history-writer 컨슈머는 chat.role.history-writer 로 켜고 끈다")
    void historyWriterRoleOwnsStreamWrites() {
        for (Class<?> type : List.of(HistoryWriterListener.class, HistoryStreamWriter.class, HistoryWriterConfig.class)) {
            assertThat(roleOf(type)).as(type.getSimpleName()).isEqualTo("chat.role.history-writer");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-3] API 역할을 끄면 욕설 탐지기(금지어 JSON 로딩)도 만들지 않는다")
    void apiRoleOff_noProfanityDetector() {
        try (ConfigurableApplicationContext ctx = run(CoreAndApiRole.class,
                Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"), "--chat.role.api=false")) {
            assertThat(ctx.getBeansOfType(ProfanityDetector.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-56] API 역할이 켜져 있으면 :profanity 모듈의 ProfanityDetector 빈이 만들어진다")
    void apiRoleOn_profanityDetectorBeanExists() {
        try (ConfigurableApplicationContext ctx = run(CoreAndApiRole.class, Map.of("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092"))) {
            assertThat(ctx.getBean(ProfanityDetector.class).maskWithAsterisks("시발 오늘")).isEqualTo("** 오늘");
        }
    }
}
