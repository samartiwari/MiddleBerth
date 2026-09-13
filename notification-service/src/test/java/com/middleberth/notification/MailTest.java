package com.middleberth.notification;

import com.middleberth.notification.repository.SentMailRepository;
import com.middleberth.notification.service.PurgeJob;
import com.middleberth.notification.send.LoggingNotifier;
import com.middleberth.notification.send.Mail;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6: booking-service says something happened, one mail goes out.
 *
 * The tests stand in for booking-service and put the events on Kafka themselves.
 * Nothing real is ever sent — the logging notifier is the default, because a load
 * test that mails a few lakh strangers is not a load test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MailTest {

    @Autowired SentMailRepository sentMailRepo;
    @Autowired LoggingNotifier notifier;
    @Autowired KafkaConnectionDetails kafka;
    @Autowired PurgeJob purgeJob;

    /**
     * Every test uses its OWN passenger, and looks only at that passenger's mail.
     *
     * They used to share 5512/A7X2, and that failed in CI roughly one run in ten.
     * Wiping the table between tests is not enough on its own: the consumer keeps
     * running, so a message published by the previous test can be handled just
     * after the wipe. It then writes the "already sent" record for 5512/A7X2, and
     * the next test's confirmation is silently skipped as a duplicate — one mail
     * where two were expected, thirty seconds of waiting, and nothing in the log
     * to say why.
     *
     * Separate ids make that impossible rather than unlikely.
     */
    @BeforeEach
    void seed() {
        sentMailRepo.deleteAllInBatch();
        notifier.reset();
    }

    /** The contract file is booking-service's own test fixture, fed in here unchanged. */
    @Test
    void a_confirmed_ticket_becomes_one_mail() throws Exception {
        publish("5512|A7X2", Files.readString(Path.of("../contracts/booking-event.json")));

        Mail mail = awaitMailsTo("samar@example.invalid", 1).get(0);
        assertThat(mail.to()).as("the address on the event, not one we looked up")
                .isEqualTo("samar@example.invalid");
        assertThat(mail.body()).contains("Samar Tiwari");
        assertThat(mail.subject()).contains("Ticket confirmed").contains("12951");
        assertThat(mail.body()).contains("B2-31")
                .as("the number a passenger quotes, not our internal request id")
                .contains("PNR 4728193056");
    }

    /** Messages arrive at least once — the outbox can hand the same note over twice. */
    @Test
    void the_same_message_twice_is_still_one_mail() {
        String event = ticket(7001, "DUP");
        publish("7001|DUP", event);
        publish("7001|DUP", event);                                   // the duplicate
        // A third message on the SAME key, so it lands in the same partition and is
        // definitely handled after both copies. Without it the test could look at
        // the mailbox before the duplicate had even been read.
        publish("7001|DUP", cancelled(7001, "DUP", "NOTHING_LEFT"));

        await(() -> mailsTo(7001, "Booking cancelled").size() == 1, "the message behind the duplicate");

        assertThat(mailsTo(7001, "Ticket confirmed")).as("the duplicate sent nothing").hasSize(1);
        assertThat(sentMailRepo.countByUserId(7001L)).isEqualTo(2);
    }

    @Test
    void a_cancellation_is_a_mail_of_its_own() {
        publish("7002|CANCEL", ticket(7002, "CANCEL"));
        publish("7002|CANCEL", cancelled(7002, "CANCEL", "PAID_AFTER_DEADLINE"));

        List<Mail> mails = awaitMailsFor(7002, 2);

        assertThat(mails.get(0).subject()).contains("Ticket confirmed");
        assertThat(mails.get(1).subject()).contains("Booking cancelled");
        assertThat(mails.get(1).body()).contains("on its way back");
    }

    /**
     * An event with no address on it — only possible for a booking made before
     * passengers were asked for. Not a crash, and it must not block what is behind it.
     */
    @Test
    void an_event_with_no_address_is_skipped_and_the_queue_carries_on() {
        publish("999|NOBODY", withoutAddress(999, "NOBODY"));
        publish("999|NOBODY", cancelled(999, "NOBODY", "NOTHING_LEFT"));   // same key, so it is next

        assertThat(awaitMailsFor(999, 1)).singleElement().satisfies(mail ->
                assertThat(mail.to()).isEqualTo("passenger999@example.invalid"));
        assertThat(sentMailRepo.countByUserId(999L))
                .as("nothing written down for the one with no address").isEqualTo(1);
    }

    /**
     * The record of a sent mail is kept only as long as Kafka keeps the message
     * that could duplicate it. Beyond that it is dead weight.
     */
    @Test
    void records_older_than_kafkas_memory_are_deleted() {
        publish("7003|PURGE", ticket(7003, "PURGE"));
        awaitMailsFor(7003, 1);
        assertThat(sentMailRepo.countByUserId(7003L)).isEqualTo(1);

        int removed = purgeJob.purgeBefore(OffsetDateTime.now().plusDays(1));   // as if a week had passed

        // The purge is deliberately indiscriminate — it sweeps the whole table, so
        // the count it returns includes anybody else's rows. Asserting it removed
        // EXACTLY one makes this test fail whenever another test's message happens
        // to land mid-run, which is a race and not a defect. What actually matters
        // is that this passenger's record is gone.
        assertThat(removed).as("swept at least this passenger's record").isGreaterThanOrEqualTo(1);
        assertThat(sentMailRepo.countByUserId(7003L)).isZero();
    }

    // ---------- helpers ----------

    private String ticket(long userId, String requestId) {
        return """
                {"type":"TICKET_CONFIRMED","userId":%d,"requestId":"%s","trainNumber":"12951",\
                "travelDate":"2026-08-25","coachClass":"3A","berth":"B2-31","reason":null,\
                "passengerName":"Passenger %d","passengerEmail":"passenger%d@example.invalid",\
                "pnr":"100000000%d"}"""
                .formatted(userId, requestId, userId, userId, userId);
    }

    private String cancelled(long userId, String requestId, String reason) {
        return """
                {"type":"BOOKING_CANCELLED","userId":%d,"requestId":"%s","trainNumber":"12951",\
                "travelDate":"2026-08-25","coachClass":"3A","berth":null,"reason":"%s",\
                "passengerName":"Passenger %d","passengerEmail":"passenger%d@example.invalid",\
                "pnr":"100000000%d"}"""
                .formatted(userId, requestId, reason, userId, userId, userId);
    }

    /** A ticket event with nobody to send it to. */
    private String withoutAddress(long userId, String requestId) {
        return """
                {"type":"TICKET_CONFIRMED","userId":%d,"requestId":"%s","trainNumber":"12951",\
                "travelDate":"2026-08-25","coachClass":"3A","berth":"B2-31","reason":null,\
                "passengerName":null,"passengerEmail":null,"pnr":null}"""
                .formatted(userId, requestId);
    }

    private void publish(String key, String body) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("booking-events", key, body));
            producer.flush();
        }
    }

    /** Only this passenger's mail — the address carries the user id. */
    private List<Mail> mailsTo(long userId) {
        return mailsTo("passenger" + userId + "@example.invalid");
    }

    private List<Mail> mailsTo(String address) {
        return notifier.sent().stream().filter(m -> address.equals(m.to())).toList();
    }

    private List<Mail> mailsTo(long userId, String subject) {
        return mailsTo(userId).stream().filter(m -> m.subject().contains(subject)).toList();
    }

    private void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /**
     * Waits for this passenger's mail, and ignores everyone else's. A message left
     * over from an earlier test can land in the mailbox at any moment; it is not
     * this test's business and must not fail it.
     */
    private List<Mail> awaitMailsFor(long userId, int expected) {
        return awaitMailsTo("passenger" + userId + "@example.invalid", expected);
    }

    private List<Mail> awaitMailsTo(String address, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && mailsTo(address).size() < expected) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(mailsTo(address)).as("mails to %s", address).hasSize(expected);
        return mailsTo(address);
    }
}
