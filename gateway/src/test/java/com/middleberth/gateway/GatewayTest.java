package com.middleberth.gateway;

import com.middleberth.gateway.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * The gateway's three jobs: who are you, too many, where to.
 *
 * booking and search are stubs that record what arrived, so these tests check
 * what the services behind the gateway would actually see — in particular the
 * X-User-Id header they trust.
 */
@Import(TestcontainersConfiguration.class)
// demo-tokens: these tests are about routing, headers and limits, not about
// logging in. Signing up two dozen accounts with BCrypt to test a route would
// only make them slow. Logging in for real is AuthTest's job.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "middleberth.auth.demo-tokens=true")
@AutoConfigureWebTestClient
class GatewayTest {

    static final StubBackend BOOKING = StubBackend.start();
    static final StubBackend SEARCH = StubBackend.start();
    static final StubBackend PAYMENT = StubBackend.start();

    /** Rate limit buckets live in Redis for the whole run, so every test gets fresh users. */
    private static final AtomicLong NEXT_USER = new AtomicLong(100_000);

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("middleberth.booking-url", BOOKING::url);
        r.add("middleberth.search-url", SEARCH::url);
        r.add("middleberth.payment-url", PAYMENT::url);
    }

    @Autowired WebTestClient web;

    @BeforeEach
    void clear() {
        BOOKING.clear();
        SEARCH.clear();
        PAYMENT.clear();
    }

    // ---------- where to ----------

    @Test
    void trains_go_to_search_and_bookings_go_to_booking() {
        web.get().uri("/api/trains").exchange().expectStatus().isOk();
        post(tokenFor(newUser()));

        assertThat(SEARCH.seen()).extracting(StubBackend.Seen::path).containsExactly("/api/trains");
        assertThat(BOOKING.seen()).extracting(StubBackend.Seen::path).containsExactly("/api/bookings");
    }

    // ---------- who are you ----------

    @Test
    void search_needs_no_login() {
        web.get().uri("/api/trains/12951/availability?date=2026-08-25&class=3A")
           .exchange().expectStatus().isOk();

        assertThat(SEARCH.seen()).hasSize(1);
    }

    @Test
    void booking_without_a_token_is_401_and_never_reaches_booking() {
        web.post().uri("/api/bookings").contentType(APPLICATION_JSON).bodyValue("{}")
           .exchange().expectStatus().isUnauthorized();

        assertThat(BOOKING.seen()).as("rejected at the door").isEmpty();
    }

    /**
     * The passenger master list holds names, emails and phone numbers — the only
     * real personal data in the system. It must be behind a token like bookings,
     * and it must carry the user id from that token, or one account could read
     * another's passengers.
     */
    @Test
    void the_passenger_list_needs_a_token_and_carries_the_user_id() {
        web.get().uri("/api/passengers").exchange().expectStatus().isUnauthorized();
        assertThat(BOOKING.seen()).as("rejected at the door").isEmpty();

        web.get().uri("/api/passengers")
           .headers(h -> h.setBearerAuth(tokenFor(5512)))
           .exchange().expectStatus().isOk();

        assertThat(BOOKING.seen()).singleElement()
                .satisfies(seen -> {
                    assertThat(seen.path()).isEqualTo("/api/passengers");
                    assertThat(seen.userIdHeader()).isEqualTo("5512");
                });
    }

    @Test
    void a_made_up_token_is_401() {
        web.post().uri("/api/bookings")
           .headers(h -> h.setBearerAuth("not.a.real-token"))
           .contentType(APPLICATION_JSON).bodyValue("{}")
           .exchange().expectStatus().isUnauthorized();

        assertThat(BOOKING.seen()).isEmpty();
    }

    @Test
    void booking_is_told_the_user_id_from_the_token() {
        post(tokenFor(5512));

        assertThat(BOOKING.seen()).singleElement()
                .extracting(StubBackend.Seen::userIdHeader).isEqualTo("5512");
    }

    /** The one that matters most. Without it, auth is decorative. */
    @Test
    void a_forged_user_id_header_is_thrown_away() {
        String token = tokenFor(5512);

        web.post().uri("/api/bookings")
           .headers(h -> {
               h.setBearerAuth(token);
               h.set("X-User-Id", "999");     // pretending to be someone else
           })
           .contentType(APPLICATION_JSON).bodyValue("{}")
           .exchange().expectStatus().isOk();

        assertThat(BOOKING.seen()).singleElement()
                .extracting(StubBackend.Seen::userIdHeader)
                .as("the token wins, the client's header is gone")
                .isEqualTo("5512");
    }

    @Test
    void search_never_receives_a_forged_user_id_either() {
        web.get().uri("/api/trains").header("X-User-Id", "999")
           .exchange().expectStatus().isOk();

        assertThat(SEARCH.seen()).singleElement()
                .extracting(StubBackend.Seen::userIdHeader).isNull();
    }

    // ---------- too many ----------

    @Test
    void booking_too_fast_gets_429() {
        String token = tokenFor(newUser());

        List<HttpStatusCode> statuses = IntStream.range(0, 8).mapToObj(i -> post(token)).toList();

        System.out.println("\n8 rapid bookings by one user -> " + statuses);
        assertThat(statuses).as("burst capacity is 3").contains(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(statuses.stream().filter(HttpStatusCode::is2xxSuccessful).count())
                .as("the burst gets through").isGreaterThanOrEqualTo(3);
    }

    /**
     * Every request in this test comes from the same IP — the test machine. So
     * this is the college-wifi case: one user hammering does not lock out the
     * person next to them, because buckets are per user, not per address.
     */
    @Test
    void one_users_limit_does_not_block_another_on_the_same_ip() {
        String alice = tokenFor(newUser());
        String bob = tokenFor(newUser());

        IntStream.range(0, 8).forEach(i -> post(alice));

        assertThat(post(alice)).as("alice is over her limit").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(post(bob).is2xxSuccessful()).as("bob, same IP, is unaffected").isTrue();
    }

    // ---------- payments (5c) ----------

    @Test
    void pay_now_needs_a_login() {
        web.post().uri("/api/bookings/A7X2/pay").exchange().expectStatus().isUnauthorized();
        assertThat(BOOKING.seen()).isEmpty();
    }

    @Test
    void pay_now_goes_to_booking_and_says_who_is_paying() {
        String token = tokenFor(5512);

        web.post().uri("/api/bookings/A7X2/pay").headers(h -> h.setBearerAuth(token))
           .exchange().expectStatus().isOk();

        assertThat(BOOKING.seen()).singleElement().satisfies(seen -> {
            assertThat(seen.path()).isEqualTo("/api/bookings/A7X2/pay");
            assertThat(seen.userIdHeader()).isEqualTo("5512");
        });
    }

    @Test
    void the_razorpay_webhook_needs_no_login_and_goes_to_payment() {
        web.post().uri("/webhooks/razorpay").contentType(APPLICATION_JSON).bodyValue("{}")
           .exchange().expectStatus().isOk();

        assertThat(PAYMENT.seen()).extracting(StubBackend.Seen::path).containsExactly("/webhooks/razorpay");
        assertThat(BOOKING.seen()).isEmpty();
    }

    /**
     * payment-service checks an HMAC over the exact bytes Razorpay sent. If the
     * gateway reformatted the JSON on the way through — even one space — every real
     * webhook would fail its signature check.
     */
    @Test
    void the_webhook_body_reaches_payment_service_byte_for_byte() {
        String oddlySpaced = "{ \"event\" :  \"payment.captured\",\"payload\":{ \"x\" : 1 } }";

        web.post().uri("/webhooks/razorpay").contentType(APPLICATION_JSON).bodyValue(oddlySpaced)
           .exchange().expectStatus().isOk();

        assertThat(PAYMENT.seen()).singleElement()
                .extracting(StubBackend.Seen::body).isEqualTo(oddlySpaced);
    }

    /** Only booking-service may create orders. There is no way in from outside. */
    @Test
    void payment_services_internal_endpoints_cannot_be_reached_from_outside() {
        String token = tokenFor(5512);

        web.post().uri("/internal/orders").headers(h -> h.setBearerAuth(token))
           .contentType(APPLICATION_JSON).bodyValue("{\"userId\":5512,\"requestId\":\"X\",\"amountPaise\":1}")
           .exchange().expectStatus().value(status ->
                   assertThat(status).as("denied, whatever the token").isIn(401, 403, 404));

        assertThat(PAYMENT.seen()).as("never reached").isEmpty();
    }

    // ---------- helpers ----------

    private long newUser() {
        return NEXT_USER.getAndIncrement();
    }

    private String tokenFor(long userId) {
        TokenResponse res = web.post().uri("/auth/token")
                .contentType(APPLICATION_JSON)
                .bodyValue(Map.of("userId", userId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        return res.token();
    }

    private HttpStatusCode post(String token) {
        return web.post().uri("/api/bookings")
                .headers(h -> h.setBearerAuth(token))
                .contentType(APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .returnResult(String.class)
                .getStatus();
    }
}
