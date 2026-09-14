package com.middleberth.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.controller.BookingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this service tells the docs page about itself.
 *
 * The gateway shows one Swagger page for the whole API and fetches this
 * description through its own routes. So it must list exactly what a browser can
 * reach through the gateway, and point "Try it out" back at the gateway rather
 * than at this service's address inside Docker.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    /**
     * An exact list, so a new endpoint cannot slip into the public docs, or go
     * missing from them, without somebody deciding it should.
     */
    @Test
    void describes_bookings_and_passengers_and_nothing_else() throws Exception {
        assertThat(names(docs().path("paths"))).containsExactlyInAnyOrder(
                "/api/bookings",
                "/api/bookings/{requestId}",
                "/api/bookings/{requestId}/cancel",
                "/api/bookings/{requestId}/pay",
                "/api/passengers",
                "/api/passengers/{id}");
    }

    /**
     * Who is calling comes from the token, and the gateway throws away any user id
     * a client sends. Listing that header would invite people to fill it in.
     */
    @Test
    void never_asks_the_caller_to_say_who_they_are() {
        assertThat(http.getForObject("/v3/api-docs", String.class)).doesNotContain(BookingController.USER_ID);
    }

    /** Every booking and passenger call needs a token, so the page offers an Authorize button. */
    @Test
    void says_every_call_needs_a_bearer_token() throws Exception {
        JsonNode docs = docs();

        assertThat(docs.at("/components/securitySchemes/bearer-jwt/scheme").asText()).isEqualTo("bearer");
        assertThat(docs.at("/security/0").has("bearer-jwt")).isTrue();
    }

    /** "/" means whatever address the docs page was opened on, which is the gateway. */
    @Test
    void sends_try_it_out_to_the_address_the_docs_came_from() throws Exception {
        assertThat(docs().at("/servers/0/url").asText()).isEqualTo("/");
    }

    private JsonNode docs() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
