package com.skhynix.chat.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 HTTP 로 띄운 앱을 두드리는 최소 클라이언트(JDK HttpClient). SSE 는 줄 단위로 읽어 이벤트로 모은다. */
final class E2eHttp {

    static final ObjectMapper MAPPER = new ObjectMapper();

    record Resp(int status, String body) {
        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    E2eHttp(int port) {
        this.base = "http://localhost:" + port;
    }

    /** @param path context-path(/chat)를 포함한 전체 경로 */
    Resp get(String path, String token) {
        return send(builder(path, token).GET().build());
    }

    Resp post(String path, String token, String jsonBody) {
        return send(builder(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8)).build());
    }

    Resp delete(String path, String token) {
        return send(builder(path, token).DELETE().build());
    }

    private HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private Resp send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(e);
        }
    }

    SseStream subscribe(String path, String token, String lastEventId) {
        return subscribe(path, token, lastEventId, "text/event-stream");
    }

    /** @param accept 클라이언트가 보내는 Accept(fetch-event-source 폴리필은 text/event-stream 하나만 보낸다) */
    SseStream subscribe(String path, String token, String lastEventId, String accept) {
        HttpRequest.Builder b = builder(path, token).header("Accept", accept).GET();
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        return new SseStream(client, b.build());
    }

    /** SSE 이벤트 하나. 주석이면 comment 만 채워진다. */
    record SseEvent(String name, String id, String data, String comment) {
    }

    /** 열린 SSE 연결. 백그라운드 스레드가 줄을 읽어 이벤트로 쌓는다. */
    static final class SseStream implements AutoCloseable {
        private final List<SseEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicInteger status = new AtomicInteger(-1);
        private volatile String contentType;
        private volatile String errorBody;
        private volatile HttpResponse<Stream<String>> response;
        private final Thread reader;

        SseStream(HttpClient client, HttpRequest request) {
            this.reader = new Thread(() -> {
                try {
                    HttpResponse<Stream<String>> r = client.send(request, HttpResponse.BodyHandlers.ofLines());
                    response = r;
                    contentType = r.headers().firstValue("Content-Type").orElse(null);
                    status.set(r.statusCode());
                    if (r.statusCode() != 200) {
                        errorBody = String.join("\n", r.body().toList());
                        return;
                    }
                    String name = null;
                    String id = null;
                    StringBuilder data = null;
                    String comment = null;
                    for (java.util.Iterator<String> it = r.body().iterator(); it.hasNext();) {
                        String line = it.next();
                        if (line.isEmpty()) {
                            if (name != null || id != null || data != null || comment != null) {
                                events.add(new SseEvent(name, id, data == null ? null : data.toString(), comment));
                            }
                            name = null;
                            id = null;
                            data = null;
                            comment = null;
                        } else if (line.startsWith("event:")) {
                            name = line.substring(6);
                        } else if (line.startsWith("id:")) {
                            id = line.substring(3);
                        } else if (line.startsWith("data:")) {
                            data = (data == null ? new StringBuilder() : data.append('\n')).append(line.substring(5));
                        } else if (line.startsWith(":")) {
                            comment = line.substring(1);
                        }
                    }
                } catch (Exception e) {
                    // 연결이 서버/클라이언트에서 닫히면 읽기가 예외로 끝난다
                } finally {
                    ended.set(true);
                }
            }, "e2e-sse-reader");
            reader.setDaemon(true);
            reader.start();
        }

        int status() {
            return status.get();
        }

        String contentType() {
            return contentType;
        }

        String errorBody() {
            return errorBody;
        }

        boolean isEnded() {
            return ended.get();
        }

        List<SseEvent> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        /** 주석(:ping)을 뺀 이벤트. */
        List<SseEvent> dataEvents() {
            return events().stream().filter(e -> e.comment() == null).toList();
        }

        @Override
        public void close() {
            HttpResponse<Stream<String>> r = response;
            if (r != null) {
                r.body().close();
            }
            reader.interrupt();
        }
    }
}
