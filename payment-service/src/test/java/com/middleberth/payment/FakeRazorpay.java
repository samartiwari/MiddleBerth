package com.middleberth.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Razorpay's API, as far as our client is concerned.
 *
 * Not a mock of our own class — a real HTTP server answering on the real paths
 * with the real response shapes. That is the only way to test that the client
 * sends the right body, the right auth header and the right idempotency key,
 * which is exactly where a payment client goes wrong.
 *
 * The JDK has an HTTP server built in, so this needs no dependency.
 */
final class FakeRazorpay {

    record Seen(String method, String path, String body, String auth, String idempotencyKey) {
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> refundsCreated = new CopyOnWriteArrayList<>();

    /** Payments already refunded, as Razorpay would report them. */
    private final List<String> existingRefunds = new CopyOnWriteArrayList<>();

    private FakeRazorpay(HttpServer server) {
        this.server = server;
    }

    static FakeRazorpay start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeRazorpay fake = new FakeRazorpay(server);
            server.createContext("/v1", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake Razorpay", e);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    int refundsCreated() {
        return refundsCreated.size();
    }

    /** Pretend this payment has already been refunded in full. */
    void alreadyRefunded(String paymentId) {
        existingRefunds.add(paymentId);
    }

    void reset() {
        seen.clear();
        refundsCreated.clear();
        existingRefunds.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/v1".length());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(), path, body,
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Refund-Idempotency")));

        String response = switch (route(exchange.getRequestMethod(), path)) {
            case "create-order" -> """
                    {"id":"order_LiveTest123","entity":"order","amount":240000,"amount_paid":0,\
                    "currency":"INR","receipt":"5512|A7X2","status":"created","created_at":1789000000}""";
            case "order-payments" -> """
                    {"entity":"collection","count":2,"items":[\
                    {"id":"pay_failed","entity":"payment","amount":240000,"currency":"INR",\
                    "status":"failed","method":"card","created_at":1789000001},\
                    {"id":"pay_LiveTest999","entity":"payment","amount":240000,"currency":"INR",\
                    "status":"captured","method":"upi","created_at":1789000002}]}""";
            case "payment-refunds" -> refundsFor(path);
            case "create-refund" -> {
                String id = "rfnd_Live" + refundsCreated.size();
                refundsCreated.add(id);
                yield """
                        {"id":"%s","entity":"refund","amount":240000,"currency":"INR",\
                        "payment_id":"pay_LiveTest999","status":"processed","speed_processed":"normal"}"""
                        .formatted(id);
            }
            default -> null;
        };

        byte[] out = (response == null ? "{\"error\":{\"description\":\"no such route\"}}" : response)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response == null ? 404 : 200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static String route(String method, String path) {
        if (method.equals("POST") && path.equals("/orders")) {
            return "create-order";
        }
        if (method.equals("GET") && path.matches("/orders/[^/]+/payments")) {
            return "order-payments";
        }
        if (method.equals("GET") && path.matches("/payments/[^/]+/refunds")) {
            return "payment-refunds";
        }
        if (method.equals("POST") && path.matches("/payments/[^/]+/refund")) {
            return "create-refund";
        }
        return "unknown";
    }

    private String refundsFor(String path) {
        String paymentId = path.split("/")[2];
        List<String> items = new ArrayList<>();
        if (existingRefunds.contains(paymentId)) {
            items.add("""
                    {"id":"rfnd_FromBefore","entity":"refund","amount":240000,"currency":"INR",\
                    "payment_id":"%s","status":"processed"}""".formatted(paymentId));
        }
        return """
                {"entity":"collection","count":%d,"items":[%s]}"""
                .formatted(items.size(), String.join(",", items));
    }
}
