package com.middleberth.gateway;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stands in for booking-service or search-service. Answers 200 to anything and
 * remembers what it was sent — in particular the X-User-Id header, which is the
 * whole point of checking the gateway.
 *
 * Reactor Netty is already on the classpath (the gateway runs on it), so this
 * costs no extra dependency.
 */
final class StubBackend {

    record Seen(String method, String path, String userIdHeader) {
    }

    private final DisposableServer server;
    private final Queue<Seen> seen = new ConcurrentLinkedQueue<>();

    private StubBackend() {
        this.server = HttpServer.create()
                .port(0)
                .handle((req, res) -> {
                    seen.add(new Seen(req.method().name(), req.uri(),
                            req.requestHeaders().get("X-User-Id")));
                    return req.receive().then(
                            res.status(200)
                               .header("Content-Type", "application/json")
                               .sendString(Mono.just("{\"stub\":true}"))
                               .then());
                })
                .bindNow();
    }

    static StubBackend start() {
        return new StubBackend();
    }

    String url() {
        return "http://localhost:" + server.port();
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    void clear() {
        seen.clear();
    }
}
