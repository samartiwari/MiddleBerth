package com.middleberth.booking;

import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer a waiting page asks for, over and over.
 *
 * Every poll used to be a query against the same Postgres the consumer needs for
 * claiming berths — fifty thousand of them in a two-minute load run, all
 * competing with the actual work. Now the answer is cached in Redis for a few
 * seconds.
 *
 * Deliberately dumb: a short life and no invalidation anywhere, so nothing has to
 * remember to clear it when a booking is paid for, expires or is cancelled. The
 * price is an answer that can be a few seconds old, on a page that polls every
 * second anyway.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutcomeCacheTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        redis.delete(redis.keys("middleberth:outcome:*"));
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "31", SeatStatus.FREE));
        });
    }

    @Test
    void the_first_poll_answers_and_leaves_the_answer_in_redis() {
        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A", TestPassenger.SOMEONE));

        var first = bookingService.outcomeOf(5512L, "A7X2");

        assertThat(first).isPresent();
        assertThat(first.get().status()).isEqualTo(BookingStatus.HELD);
        assertThat(redis.opsForValue().get("middleberth:outcome:5512|A7X2"))
                .as("kept for the next poll, a second from now").isNotNull();
    }

    /**
     * Proof that the second poll never touched the database: the row is deleted
     * underneath it, and the answer still comes back.
     */
    @Test
    void the_next_poll_does_not_touch_the_database() {
        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A", TestPassenger.SOMEONE));
        bookingService.outcomeOf(5512L, "A7X2");          // fills the cache

        bookingRepo.deleteAllInBatch();                   // Postgres now has nothing to say

        assertThat(bookingService.outcomeOf(5512L, "A7X2"))
                .as("answered from Redis, not from the row that is gone").isPresent();
    }

    /** Nothing is cached for a request nobody has made, so PENDING stays honest. */
    @Test
    void an_unknown_request_is_not_cached_as_an_answer() {
        assertThat(bookingService.outcomeOf(5512L, "NEVER-ASKED")).isEmpty();
        assertThat(redis.opsForValue().get("middleberth:outcome:5512|NEVER-ASKED")).isNull();
    }

    /**
     * The cache is per user as well as per booking. Two people using the same
     * request id — which is allowed, ids are unique per user — must never see each
     * other's answer.
     */
    @Test
    void two_people_using_the_same_request_id_get_their_own_answers() {
        bookingService.book(new BookingCommand("SAME", 1L, "12951", DATE, "3A", TestPassenger.SOMEONE));
        bookingService.book(new BookingCommand("SAME", 2L, "12951", DATE, "3A", TestPassenger.SOMEONE));

        var first = bookingService.outcomeOf(1L, "SAME").orElseThrow();
        var second = bookingService.outcomeOf(2L, "SAME").orElseThrow();

        assertThat(first.status()).isEqualTo(BookingStatus.HELD);
        assertThat(second.status()).as("only one berth, so the second waits")
                .isEqualTo(BookingStatus.WAITLIST_HELD);
    }
}
