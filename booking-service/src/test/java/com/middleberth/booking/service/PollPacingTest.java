package com.middleberth.booking.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long a waiting page is told to leave it before asking again.
 *
 * Every page used to ask every half second, however long it had already waited.
 * Past the rate the booking threads could keep up with, that made polls 86% of all
 * requests, thirteen for every booking, and the answers came slower still because
 * the CPU was busy saying "not yet". Somebody who has waited ten seconds gains
 * nothing by asking twice a second.
 */
class PollPacingTest {

    @ParameterizedTest(name = "waited {0} ms, ask again in {1} ms")
    @CsvSource({
            "0,       500",
            "1999,    500",
            "2000,   1000",
            "4999,   1000",
            "5000,   2000",
            "14999,  2000",
            "15000,  4000",
            "120000, 4000",
    })
    void the_longer_a_booking_has_waited_the_longer_the_page_is_told_to_wait(long waitedMs, long askAgainMs) {
        assertThat(PollPacing.after(Duration.ofMillis(waitedMs))).isEqualTo(Duration.ofMillis(askAgainMs));
    }

    /** Redis has no note of when it was accepted, so there is no age to go on. */
    @Test
    void a_booking_of_unknown_age_is_asked_about_every_second() {
        assertThat(PollPacing.forUnknownWait()).isEqualTo(Duration.ofSeconds(1));
    }
}
