package com.middleberth.search;

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
 * What this service tells the docs page about itself: the two read-only
 * endpoints, open to anyone, with "Try it out" pointed back at the gateway.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsTest {

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;

    @Test
    void describes_the_train_list_and_availability_and_nothing_else() throws Exception {
        assertThat(names(docs().path("paths"))).containsExactlyInAnyOrder(
                "/api/trains",
                "/api/trains/{trainNumber}/availability");
    }

    /** Searching needs no login, so the docs must not ask for a token. */
    @Test
    void asks_for_no_token() throws Exception {
        assertThat(docs().has("security")).isFalse();
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
