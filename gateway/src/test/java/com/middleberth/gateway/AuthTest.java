package com.middleberth.gateway;

import com.middleberth.gateway.dto.Credentials;
import com.middleberth.gateway.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Real accounts: sign up, log in, and be refused.
 *
 * Deliberately WITHOUT demo-tokens, unlike GatewayTest — so this also proves the
 * default. An account is the only way in unless somebody turns that endpoint on
 * for a load test.
 *
 * Each test uses its own email address: the failed-attempt counters live in Redis
 * for the whole run, so a shared address would mean one test locking out another.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class AuthTest {

    private static final StubBackend BOOKING = StubBackend.start();
    private static final AtomicInteger NEXT = new AtomicInteger();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("middleberth.booking-url", BOOKING::url);
        r.add("middleberth.search-url", BOOKING::url);
        r.add("middleberth.payment-url", BOOKING::url);
    }

    @Autowired WebTestClient web;

    /**
     * The whole point: an account, then a token, then that token being accepted
     * downstream as a real identity.
     */
    @Test
    void signing_up_gives_a_token_that_works_as_that_user() {
        String email = freshEmail();

        TokenResponse token = web.post().uri("/auth/signup")
                .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "correct horse battery"))
                .exchange().expectStatus().isCreated()
                .expectBody(TokenResponse.class).returnResult().getResponseBody();

        assertThat(token.token()).isNotBlank();
        assertThat(token.expiresInSeconds()).isEqualTo(3600);

        BOOKING.clear();
        web.get().uri("/api/passengers")
           .headers(h -> h.setBearerAuth(token.token()))
           .exchange().expectStatus().isOk();

        assertThat(BOOKING.seen()).singleElement().satisfies(seen ->
                assertThat(seen.userIdHeader())
                        .as("the account's own id, from the token")
                        .isNotBlank());
    }

    @Test
    void logging_in_with_the_right_password_gives_a_token() {
        String email = freshEmail();
        signUp(email, "correct horse battery");

        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "correct horse battery"))
           .exchange().expectStatus().isOk()
           .expectBody(TokenResponse.class);
    }

    /**
     * Both failures answer the same way on purpose. Saying "no such account" for
     * one and "wrong password" for the other tells a stranger which addresses are
     * registered here.
     */
    @Test
    void a_wrong_password_and_an_unknown_account_answer_identically() {
        String email = freshEmail();
        signUp(email, "correct horse battery");

        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "not the right password"))
           .exchange().expectStatus().isUnauthorized();

        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(freshEmail(), "not the right password"))
           .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void the_same_email_cannot_have_two_accounts() {
        String email = freshEmail();
        signUp(email, "correct horse battery");

        web.post().uri("/auth/signup")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "another password"))
           .exchange().expectStatus().isEqualTo(409);
    }

    /** Capitalising your own address should not cost you your account. */
    @Test
    void an_email_is_the_same_email_whatever_its_case() {
        String email = freshEmail();
        signUp(email, "correct horse battery");

        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON)
           .bodyValue(new Credentials(email.toUpperCase(), "correct horse battery"))
           .exchange().expectStatus().isOk();
    }

    @Test
    void a_nonsense_email_or_a_short_password_is_refused() {
        web.post().uri("/auth/signup")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials("not-an-address", "long enough"))
           .exchange().expectStatus().isBadRequest();

        web.post().uri("/auth/signup")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(freshEmail(), "short"))
           .exchange().expectStatus().isBadRequest();
    }

    /**
     * A login endpoint with no limit is a password guessing machine. Counted per
     * account, so guessing one person's password cannot lock out everybody who
     * happens to share their wifi.
     */
    @Test
    void guessing_is_stopped_after_a_few_wrong_answers() {
        String email = freshEmail();
        signUp(email, "correct horse battery");

        for (int attempt = 1; attempt <= 5; attempt++) {
            web.post().uri("/auth/login")
               .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "wrong guess number " + attempt))
               .exchange().expectStatus().isUnauthorized();
        }

        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "wrong guess number 6"))
           .exchange().expectStatus().isEqualTo(429);

        // And the real password is refused too, while the lockout stands — that is
        // the cost of this protection, and it is the right way round.
        web.post().uri("/auth/login")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, "correct horse battery"))
           .exchange().expectStatus().isEqualTo(429);
    }

    /** The demo endpoint is not there unless something turns it on. */
    @Test
    void a_token_cannot_be_had_by_just_naming_a_user_id() {
        web.post().uri("/auth/token")
           .contentType(APPLICATION_JSON).bodyValue("{\"userId\":5512}")
           .exchange().expectStatus().isNotFound();
    }

    /** And the docs page does not offer an endpoint that is not there. */
    @Test
    void the_docs_never_offer_the_demo_token_endpoint() {
        web.get().uri("/v3/api-docs").exchange()
           .expectStatus().isOk()
           .expectBody()
           .jsonPath("$.paths['/auth/login']").exists()
           .jsonPath("$.paths['/auth/token']").doesNotExist();
    }

    // ---------- helpers ----------

    private void signUp(String email, String password) {
        web.post().uri("/auth/signup")
           .contentType(APPLICATION_JSON).bodyValue(new Credentials(email, password))
           .exchange().expectStatus().isCreated();
    }

    private static String freshEmail() {
        return "person" + NEXT.incrementAndGet() + "@example.invalid";
    }
}
