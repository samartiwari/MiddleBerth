package com.middleberth.booking;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stands in for payment-service's /internal/orders. Real HTTP on a random port,
 * using the HTTP server built into the JDK — no extra dependency.
 *
 * Remembers what it was sent, and can be switched to fail, for the
 * "payment-service is down" case.
 */
final class StubPaymentServer {

    private final HttpServer server;
    private final Queue<String> requests = new ConcurrentLinkedQueue<>();
    private volatile boolean down;

    private StubPaymentServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/orders", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (down) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String amount = body.replaceAll(".*\"amountPaise\"\\s*:\\s*(\\d+).*", "$1");
            byte[] reply = ("{\"orderId\":\"order_stubtest\",\"amountPaise\":" + amount
                    + ",\"currency\":\"INR\",\"keyId\":\"rzp_test_stub\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    static StubPaymentServer start() {
        try {
            return new StubPaymentServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    void reset() {
        requests.clear();
        down = false;
    }

    void goDown() {
        down = true;
    }
}
