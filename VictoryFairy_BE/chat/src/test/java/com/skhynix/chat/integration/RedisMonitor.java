package com.skhynix.chat.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 공유 Redis 컨테이너에 {@code MONITOR} 를 걸어 "어떤 명령이 어떤 키에 나갔는가"를 원문 그대로 모은다.
 *
 * <p>{@code INFO commandstats} 는 명령 이름별 호출 수만 알려 줘서 "presence 키에 대한 명령만 제외"를 할 수 없다.
 * MONITOR 는 키까지 보이고, Lua 스크립트 안에서 실행된 명령도 {@code [0 lua]} 출처로 찍힌다.
 */
final class RedisMonitor implements AutoCloseable {

    /** MONITOR 한 줄. {@code lua} 는 스크립트 안에서 실행된 명령이면 true. */
    record Entry(String command, List<String> args, boolean lua, String raw) {

        boolean touches(String keyFragment) {
            return args.stream().anyMatch(a -> a.contains(keyFragment));
        }
    }

    private final Socket socket;
    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean();

    RedisMonitor() {
        try {
            socket = new Socket(Containers.redisHost(), Containers.redisPort());
            socket.getOutputStream().write("MONITOR\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread reader = new Thread(this::read, "redis-monitor");
        reader.setDaemon(true);
        reader.start();
        // 모니터가 실제로 붙었는지 — 자기 PING 이 보이면 붙은 것이다
        try (Socket probe = new Socket(Containers.redisHost(), Containers.redisPort())) {
            String marker = "monitor-ready-" + System.nanoTime();
            probe.getOutputStream().write(("ECHO " + marker + "\r\n").getBytes(StandardCharsets.UTF_8));
            probe.getOutputStream().flush();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (snapshot().stream().anyMatch(e -> e.raw().contains(marker))) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new IllegalStateException("MONITOR 가 붙지 않았다");
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void read() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = in.readLine()) != null) {
                Entry entry = parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException ignored) {
            // 닫는 중
        }
    }

    /** {@code +1760000000.123456 [0 127.0.0.1:5555] "zadd" "chat:presence:G" "1" "7"} */
    static Entry parse(String line) {
        if (!line.startsWith("+") || !line.contains("] ")) {
            return null;
        }
        int bracket = line.indexOf('[');
        int close = line.indexOf("] ");
        if (bracket < 0 || close < bracket) {
            return null;
        }
        boolean lua = line.substring(bracket, close).contains("lua");
        List<String> tokens = tokens(line.substring(close + 2));
        if (tokens.isEmpty()) {
            return null;
        }
        return new Entry(tokens.get(0).toLowerCase(Locale.ROOT), tokens.subList(1, tokens.size()), lua, line);
    }

    private static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '"') {
                i++;
                continue;
            }
            StringBuilder token = new StringBuilder();
            i++;
            while (i < text.length() && text.charAt(i) != '"') {
                if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                    i++;
                }
                token.append(text.charAt(i));
                i++;
            }
            i++;
            result.add(token.toString());
        }
        return result;
    }

    List<Entry> snapshot() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    int mark() {
        return entries.size();
    }

    List<Entry> since(int mark) {
        List<Entry> all = snapshot();
        return all.subList(Math.min(mark, all.size()), all.size());
    }

    /**
     * presence 키({@code chat:presence:}·{@code chat:presence-owner:})에 대한 명령을 뺀다. 스크립트 안에서 실행된 명령은 직전의
     * 최상위 명령(EVALSHA)에 딸린 것이므로 그 EVALSHA 가 presence 키를 다뤘다면 함께 뺀다 — 스크립트 안의 TIME 처럼 키 인자가
     * 없는 명령까지 같은 기준으로 걸러진다.
     */
    static List<Entry> withoutPresence(List<Entry> entries) {
        List<Entry> kept = new ArrayList<>();
        boolean currentIsPresence = false;
        for (Entry entry : entries) {
            if (!entry.lua()) {
                currentIsPresence = entry.touches("chat:presence");
            }
            boolean presence = entry.lua() ? currentIsPresence || entry.touches("chat:presence") : currentIsPresence;
            if (!presence) {
                kept.add(entry);
            }
        }
        return kept;
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            socket.close();
        } catch (IOException ignored) {
            // 닫는 중
        }
    }
}
