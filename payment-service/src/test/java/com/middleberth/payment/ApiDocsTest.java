package com.middleberth.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * What this service tells the docs page about itself: only the webhook.
 *
 * /internal/orders is for booking-service alone and has no route at the gateway.
 * Describing it publicly would advertise something nobody outside can call.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    @Test
    void describes_only_the_webhook() throws Exception {
        assertThat(names(docs().path("paths"))).containsExactly("/webhooks/razorpay");
    }

    @Test
    void never_mentions_the_internal_endpoints() {
        assertThat(http.getForObject("/v3/api-docs", String.class)).doesNotContain("/internal");
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
