package com.middleberth.booking;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test the whole project exists for.
 *
 * 24 berths. 500 people arriving in the same instant. Nobody may get a berth
 * somebody else already has.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatClaimConcurrencyTest {

    private static final int SEATS = 24;
    private static final int THREADS = 500;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);
    private static final String CLASS = "3A";

    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;

    @Test
    void concurrent_requests_never_double_book() throws Exception {
        Long trainId = seedTrainWith24FreeSeats();

        Queue<Long> claimedSeatIds = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        CountDownLatch releaseThemAll = new CountDownLatch(1);
        CountDownLatch everyoneFinished = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    releaseThemAll.await();          // all 500 wait here...
                    tx.executeWithoutResult(status ->
                            seatRepo.claimFreeSeat(trainId, DATE, CLASS).ifPresent(seat -> {
                                seat.setStatus(SeatStatus.HELD);
                                claimedSeatIds.add(seat.getId());
                            }));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    everyoneFinished.countDown();
                }
            });
        }

        long startedAt = System.nanoTime();
        releaseThemAll.countDown();                  // ...and go, all at once
        boolean finished = everyoneFinished.await(120, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
        pool.shutdownNow();

        System.out.printf("%n%d threads finished in %d ms, %d seats claimed%n",
                THREADS, tookMs, claimedSeatIds.size());

        assertThat(finished).as("all threads finished").isTrue();
        assertThat(failures).as("no thread blew up").isEmpty();

        // Exactly the 24 berths that existed were handed out.
        assertThat(claimedSeatIds).as("seats handed out").hasSize(SEATS);

        // THE assertion. 24 claims, 24 different berths. Nobody shares.
        assertThat(new HashSet<>(claimedSeatIds)).as("distinct berths").hasSize(SEATS);

        List<Seat> seats = seatRepo.findAll();
        assertThat(seats).hasSize(SEATS);
        assertThat(seats).as("no berth left free").noneMatch(s -> s.getStatus() == SeatStatus.FREE);
        assertThat(seats).as("every berth held").allMatch(s -> s.getStatus() == SeatStatus.HELD);
    }

    private Long seedTrainWith24FreeSeats() {
        return tx.execute(status -> {
            seatRepo.deleteAllInBatch();
            trainRepo.deleteAllInBatch();
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            for (int n = 1; n <= SEATS; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, CLASS, "B2",
                        String.valueOf(n), SeatStatus.FREE));
            }
            return train.getId();
        });
    }
}
