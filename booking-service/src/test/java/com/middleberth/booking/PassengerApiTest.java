package com.middleberth.booking;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.PassengerDetails;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The passenger master list — the same idea as IRCTC's.
 *
 * You add people once and pick them at booking time, instead of typing a name,
 * email and phone number at 10:00:00 while the quota empties. Which is also why
 * none of this is in the booking path: the list is read before the rush, and the
 * booking request carries the chosen passenger's details inline.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PassengerApiTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "31", SeatStatus.FREE));
        });
    }

    @Test
    void a_saved_passenger_comes_back_in_the_list() {
        ResponseEntity<String> added = add(5512, """
                {"name":"Samar Tiwari","email":"samar@example.invalid","phone":"9876543210"}""");

        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody()).contains("\"name\":\"Samar Tiwari\"").contains("\"id\":");

        assertThat(list(5512).getBody())
                .contains("Samar Tiwari")
                .contains("samar@example.invalid")
                .contains("9876543210");
    }

    /** The same rule as bookings: you only ever see your own. */
    @Test
    void nobody_can_see_anyone_elses_list() {
        add(5512, """
                {"name":"Samar Tiwari","email":"samar@example.invalid","phone":"9876543210"}""");

        assertThat(list(7731).getBody()).as("a different account").isEqualTo("[]");
    }

    @Test
    void deleting_someone_elses_passenger_is_a_404_not_a_delete() {
        String body = add(5512, """
                {"name":"Samar Tiwari","email":"samar@example.invalid","phone":"9876543210"}""").getBody();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        ResponseEntity<String> theft = delete(7731, id);

        assertThat(theft.getStatusCode()).as("and it says nothing about whose it is")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list(5512).getBody()).as("still there").contains("Samar Tiwari");

        assertThat(delete(5512, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(5512).getBody()).isEqualTo("[]");
    }

    @Test
    void the_same_person_twice_is_refused() {
        String person = """
                {"name":"Samar Tiwari","email":"samar@example.invalid","phone":"9876543210"}""";
        add(5512, person);

        ResponseEntity<String> again = add(5512, person);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("PASSENGER_EXISTS");
    }

    /**
     * A mistyped address is a confirmed ticket whose mail nobody ever receives, so
     * it is refused at the door rather than discovered later.
     */
    @Test
    void a_bad_email_or_phone_number_is_refused() {
        assertThat(add(5512, """
                {"name":"Samar","email":"not-an-address","phone":"9876543210"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(add(5512, """
                {"name":"Samar","email":"samar@example.invalid","phone":"12345"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The booking keeps a copy, not a pointer. So the ticket still says who it was
     * issued to even after the master list entry is gone.
     */
    @Test
    void the_booking_keeps_its_own_copy_of_the_passenger() {
        PassengerDetails passenger =
                new PassengerDetails("Samar Tiwari", "samar@example.invalid", "9876543210");
        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A", passenger));

        var booking = bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow();
        assertThat(booking.getPassenger().getName()).isEqualTo("Samar Tiwari");
        assertThat(booking.getPassenger().getEmail()).isEqualTo("samar@example.invalid");
        assertThat(booking.getPassenger().getPhone()).isEqualTo("9876543210");
    }

    // ---------- helpers ----------

    private ResponseEntity<String> add(long userId, String body) {
        return http.postForEntity("/api/passengers", new HttpEntity<>(body, asUser(userId)), String.class);
    }

    private ResponseEntity<String> list(long userId) {
        return http.exchange("/api/passengers", HttpMethod.GET,
                new HttpEntity<>(asUser(userId)), String.class);
    }

    private ResponseEntity<String> delete(long userId, long id) {
        return http.exchange("/api/passengers/" + id, HttpMethod.DELETE,
                new HttpEntity<>(asUser(userId)), String.class);
    }

    private HttpHeaders asUser(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        return headers;
    }
}
