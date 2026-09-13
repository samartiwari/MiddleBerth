package com.middleberth.booking;

import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingPayments;
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

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5c: Pay Now. booking-service checks the hold is yours and still live,
 * then asks payment-service (a stub here) for an order.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PayNowTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired BookingService bookingService;
    @Autowired BookingPayments payments;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubPaymentServer paymentService;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        paymentService.reset();
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "1", SeatStatus.FREE));
        });
    }

    /**
     * Charged the fare PLUS the convenience fee, and told the breakdown.
     *
     * Somebody paying more than the fare is entitled to see why, and the two halves
     * behave differently later: cancelling gives the fare back and keeps the fee,
     * which is what pays the gateway's cut.
     */
    @Test
    void pay_now_charges_the_fare_plus_the_convenience_fee() {
        book("A7X2", 5512);

        ResponseEntity<String> res = payNow("A7X2", 5512);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .contains("\"orderId\":\"order_stubtest\"")
                .contains("\"amountPaise\":247200")            // 2,400 fare + 3%
                .contains("\"baseFarePaise\":240000")
                .contains("\"convenienceFeePaise\":7200")
                .contains("\"keyId\":\"rzp_test_stub\"")
                .contains("\"payBy\"");
    }

    @Test
    void payment_service_is_asked_for_the_right_booking_and_amount() {
        book("A7X2", 5512);
        payNow("A7X2", 5512);

        // Both numbers travel: what to charge, and how much of it is the ticket.
        // payment-service hands money back later and must not have to know how a
        // fare is built up to do it.
        assertThat(paymentService.requests()).singleElement().asString()
                .contains("\"userId\":5512")
                .contains("\"requestId\":\"A7X2\"")
                .contains("\"amountPaise\":247200")
                .contains("\"refundablePaise\":240000");
    }

    @Test
    void a_waitlist_hold_can_be_paid_for_too() {
        book("A", 1);                                    // takes the only berth
        book("B", 2);                                    // waitlist

        ResponseEntity<String> res = payNow("B", 2);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).as("full fare, as on IRCTC").contains("\"amountPaise\":247200");
    }

    @Test
    void someone_elses_booking_is_404() {
        book("A7X2", 5512);

        ResponseEntity<String> res = payNow("A7X2", 7731);   // a different user

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(paymentService.requests()).as("payment-service never asked").isEmpty();
    }

    /** The cushion is for payments already started — not for starting new ones late. */
    @Test
    void pay_now_after_the_deadline_is_refused() {
        book("A7X2", 5512);
        jdbc.update("UPDATE booking SET pay_by = now() - interval '10 seconds' WHERE request_id = 'A7X2'");

        ResponseEntity<String> res = payNow("A7X2", 5512);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).contains("expired");
        assertThat(paymentService.requests()).isEmpty();
    }

    @Test
    void an_already_paid_booking_is_refused() {
        book("A7X2", 5512);
        payments.markPaid(5512L, "A7X2", Instant.now());

        ResponseEntity<String> res = payNow("A7X2", 5512);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).contains("CONFIRMED");
    }

    /**
     * A regret leaves no row, so there is not even a booking to refuse payment
     * for — the answer is 404, not 409. That is the honest one: we are not saying
     * "this booking cannot be paid for", we are saying there is no booking.
     */
    @Test
    void a_regretted_booking_has_nothing_to_pay_for() {
        book("A", 1);                                    // berth
        book("B", 2);                                    // the one waitlist slot
        assertThat(book("C", 3)).isEqualTo(BookingStatus.REGRETTED);

        assertThat(payNow("C", 3).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bookingRepo.findByUserIdAndRequestId(3L, "C"))
                .as("nothing was written down").isEmpty();
    }

    @Test
    void payment_service_down_is_503_and_the_hold_is_untouched() {
        book("A7X2", 5512);
        paymentService.goDown();

        ResponseEntity<String> res = payNow("A7X2", 5512);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).contains("PAYMENT_UNAVAILABLE");
        assertThat(bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .as("still held — they can just try again").isEqualTo(BookingStatus.HELD);
    }

    // ---------- helpers ----------

    private BookingStatus book(String requestId, long userId) {
        return bookingService.book(new BookingCommand(requestId, userId, "12951", DATE, "3A", TestPassenger.SOMEONE)).status();
    }

    private ResponseEntity<String> payNow(String requestId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));   // what the gateway would set
        return http.exchange("/api/bookings/{id}/pay", HttpMethod.POST,
                new HttpEntity<>(headers), String.class, requestId);
    }
}
