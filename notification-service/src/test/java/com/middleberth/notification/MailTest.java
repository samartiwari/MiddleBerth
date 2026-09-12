package com.middleberth.notification;

import com.middleberth.notification.domain.Passenger;
import com.middleberth.notification.repository.PassengerRepository;
import com.middleberth.notification.repository.SentMailRepository;
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

    @Autowired PassengerRepository passengerRepo;
    @Autowired SentMailRepository sentMailRepo;
    @Autowired LoggingNotifier notifier;
    @Autowired KafkaConnectionDetails kafka;

    @BeforeEach
    void seed() {
        sentMailRepo.deleteAllInBatch();
        passengerRepo.deleteAllInBatch();
        passengerRepo.save(new Passenger(5512L, "samar@example.invalid"));
        notifier.reset();
    }

    /** The contract file is booking-service's own test fixture, fed in here unchanged. */
    @Test
    void a_confirmed_ticket_becomes_one_mail() throws Exception {
        publish("5512|A7X2", Files.readString(Path.of("../contracts/booking-event.json")));

        Mail mail = awaitMails(1).get(0);
        assertThat(mail.to()).isEqualTo("samar@example.invalid");
        assertThat(mail.subject()).contains("Ticket confirmed").contains("12951");
        assertThat(mail.body()).contains("B2-31").contains("A7X2");
    }

    /** Messages arrive at least once — the outbox can hand the same note over twice. */
    @Test
    void the_same_message_twice_is_still_one_mail() throws Exception {
        String event = Files.readString(Path.of("../contracts/booking-event.json"));
        publish("5512|A7X2", event);
        publish("5512|A7X2", event);                                  // the duplicate
        // A third message on the SAME key, so it lands in the same partition and is
        // definitely handled after both copies. Without it the test could look at
        // the mailbox before the duplicate had even been read.
        publish("5512|A7X2", cancelled(5512, "A7X2", "NOTHING_LEFT"));

        await(() -> mailsSaying("Booking cancelled").size() == 1, "the message behind the duplicate");

        assertThat(mailsSaying("Ticket confirmed")).as("the duplicate sent nothing").hasSize(1);
        assertThat(sentMailRepo.count()).isEqualTo(2);
    }

    @Test
    void a_cancellation_is_a_mail_of_its_own() throws Exception {
        publish("5512|A7X2", Files.readString(Path.of("../contracts/booking-event.json")));
        publish("5512|A7X2", cancelled(5512, "A7X2", "PAID_AFTER_DEADLINE"));

        List<Mail> mails = awaitMails(2);
        assertThat(mails.get(1).subject()).contains("Booking cancelled");
        assertThat(mails.get(1).body()).contains("on its way back");
    }

    /** No address is not a crash, and must not block the messages behind it. */
    @Test
    void an_unknown_passenger_is_skipped_and_the_queue_carries_on() {
        publish("999|NOBODY", ticket(999, "NOBODY"));         // nobody to write to

        passengerRepo.save(new Passenger(999L, "found@example.invalid"));
        publish("999|NOBODY", cancelled(999, "NOBODY", "NOTHING_LEFT"));   // same key, so it is next

        assertThat(awaitMails(1)).singleElement().satisfies(mail ->
                assertThat(mail.to()).isEqualTo("found@example.invalid"));
        assertThat(sentMailRepo.count()).as("nothing written down for the one with no address").isEqualTo(1);
    }

    // ---------- helpers ----------

    private String ticket(long userId, String requestId) {
        return """
                {"type":"TICKET_CONFIRMED","userId":%d,"requestId":"%s","trainNumber":"12951",\
                "travelDate":"2026-08-25","coachClass":"3A","berth":"B2-31","reason":null}"""
                .formatted(userId, requestId);
    }

    private String cancelled(long userId, String requestId, String reason) {
        return """
                {"type":"BOOKING_CANCELLED","userId":%d,"requestId":"%s","trainNumber":"12951",\
                "travelDate":"2026-08-25","coachClass":"3A","berth":null,"reason":"%s"}"""
                .formatted(userId, requestId, reason);
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

    private List<Mail> mailsSaying(String subject) {
        return notifier.sent().stream().filter(m -> m.subject().contains(subject)).toList();
    }

    private void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private List<Mail> awaitMails(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && notifier.sent().size() < expected) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(notifier.sent()).as("mails sent").hasSize(expected);
        return notifier.sent();
    }
}
