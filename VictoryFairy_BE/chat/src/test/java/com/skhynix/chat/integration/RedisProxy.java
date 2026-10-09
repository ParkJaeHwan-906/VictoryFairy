package com.skhynix.chat.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 앱과 공유 Redis 컨테이너 사이에 끼우는 TCP 프록시. Redis 장애를 컨테이너를 건드리지 않고 만든다.
 *
 * <ul>
 *   <li>{@link Mode#UP}: 그대로 중계한다.</li>
 *   <li>{@link Mode#DOWN}: 리스너를 닫고 열린 연결을 끊는다 — 접속 거부(프로세스가 죽은 Redis).</li>
 *   <li>{@link Mode#BLACKHOLE}: 연결은 받되 아무것도 중계하지 않는다 — 응답 없는 Redis(명령 타임아웃까지 대기).</li>
 * </ul>
 *
 * <p>docker pause/stop 을 쓰지 않는 이유: 컨테이너를 재시작하면 호스트 포트가 바뀌고, pause 는 공유 컨테이너를 쓰는 다른
 * 테스트와 구독 연결까지 얼린다. 프록시는 포트를 고정한 채 이 앱의 연결만 끊는다.
 */
final class RedisProxy implements AutoCloseable {

    enum Mode { UP, DOWN, BLACKHOLE }

    private final String upstreamHost;
    private final int upstreamPort;
    private final int port;
    private volatile Mode mode = Mode.DOWN;
    private ServerSocket server;
    private final Set<Socket> live = ConcurrentHashMap.newKeySet();

    RedisProxy(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            this.port = probe.getLocalPort();
        }
    }

    int port() {
        return port;
    }

    String host() {
        return "127.0.0.1";
    }

    Mode mode() {
        return mode;
    }

    synchronized void set(Mode next) throws IOException {
        Mode previous = mode;
        mode = next;
        switch (next) {
            case DOWN -> {
                closeListener();
                closeAll();
            }
            case UP -> {
                // 블랙홀이었던 연결은 이미 죽은 것이므로 끊어 클라이언트가 새로 붙게 한다(Redis 가 재시작된 상황)
                if (previous == Mode.BLACKHOLE) {
                    closeAll();
                }
                openListener();
            }
            case BLACKHOLE -> openListener();
        }
    }

    private void openListener() throws IOException {
        if (server != null && !server.isClosed()) {
            return;
        }
        ServerSocket created = new ServerSocket();
        created.setReuseAddress(true);
        created.bind(new InetSocketAddress("127.0.0.1", port), 256);
        server = created;
        Thread acceptor = new Thread(() -> acceptLoop(created), "redis-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void closeListener() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 닫는 중
            }
            server = null;
        }
    }

    private void closeAll() {
        for (Socket socket : live) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 닫는 중
            }
        }
        live.clear();
    }

    private void acceptLoop(ServerSocket listener) {
        while (!listener.isClosed()) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                return;
            }
            live.add(client);
            if (mode == Mode.BLACKHOLE) {
                continue;
            }
            try {
                Socket upstream = new Socket(upstreamHost, upstreamPort);
                live.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException e) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // 닫는 중
                }
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (mode == Mode.UP) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 어느 한쪽이 끊김
            } finally {
                try {
                    from.close();
                } catch (IOException ignored) {
                    // 닫는 중
                }
                try {
                    to.close();
                } catch (IOException ignored) {
                    // 닫는 중
                }
                live.remove(from);
                live.remove(to);
            }
        }, "redis-proxy-pump");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        mode = Mode.DOWN;
        closeListener();
        closeAll();
    }
}
