package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 열린 SSE 연결이 있어도 컨텍스트 종료가 웹 서버 graceful shutdown 타임아웃(기본 30초)까지 붙잡히지 않아야 한다.
 * 레지스트리가 전 구독을 먼저 닫는 정지 순서(SmartLifecycle phase)가 실제로 동작하는지를 앱 전체로 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChatGracefulShutdownIT {

    @Test
    @DisplayName("[CHAT-GC-33] 열린 SSE 구독 3개가 있는 채 컨텍스트를 닫아도 graceful 타임아웃(30초)을 기다리지 않고 수 초 안에 끝나며 클라이언트 스트림도 닫힌다")
    void closeWithOpenSseConnections_doesNotWaitForGracefulTimeout() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        AppLauncher app = new AppLauncher();
        String room = app.room();
        String[] tokens = {app.userToken(7301), app.userToken(7302), app.userToken(7303)};
        app.start("--server.shutdown=graceful", "--spring.lifecycle.timeout-per-shutdown-phase=30s");
        E2eHttp http = app.http();
        E2eHttp.SseStream[] streams = new E2eHttp.SseStream[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            streams[i] = http.subscribe("/chat/rooms/" + room + "/subscribe", tokens[i], null);
        }
        var registry = app.context.getBean(com.skhynix.chat.realtime.SseEmitterRegistry.class);
        await().atMost(Duration.ofSeconds(15)).until(() -> registry.count(room) == 3);

        long start = System.nanoTime();
        app.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("열린 SSE 3개를 둔 채 종료하는 데 걸린 시간(ms)").isLessThan(12_000);
        for (E2eHttp.SseStream stream : streams) {
            await().atMost(Duration.ofSeconds(5)).until(stream::isEnded);
        }
    }
}
