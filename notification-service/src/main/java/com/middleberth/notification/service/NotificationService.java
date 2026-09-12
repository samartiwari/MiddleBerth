package com.middleberth.notification.service;

import com.middleberth.notification.domain.SentMail;
import com.middleberth.notification.dto.NotificationEvent;
import com.middleberth.notification.repository.SentMailRepository;
import com.middleberth.notification.send.MailWriter;
import com.middleberth.notification.send.Notifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * One message in, one mail out — at most.
 *
 *   1. have we sent this already?     a short query
 *   2. send it                        the mail server, NO transaction open
 *   3. write down that we sent it     a short save
 *
 * Who it goes to arrives WITH the event. This service used to keep its own table
 * of addresses, which was a guess with nothing keeping it in step with the
 * bookings it described.
 *
 * Sending comes before writing it down. If the pod dies in between, the message
 * is delivered again later and the passenger gets the same mail twice. Annoying.
 * The other order would lose the mail entirely instead, which is worse.
 *
 * Two copies of the same message cannot race here — they share a Kafka key, so one
 * thread handles them one after the other. If that ever changes, the UNIQUE
 * constraint underneath is still the real guarantee.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SentMailRepository sentMailRepo;
    private final MailWriter writer;
    private final Notifier notifier;

    public Sent handle(NotificationEvent event) {
        String type = event.type().name();
        if (sentMailRepo.existsByUserIdAndRequestIdAndType(event.userId(), event.requestId(), type)) {
            return Sent.ALREADY_SENT;
        }

        if (event.passengerEmail() == null || event.passengerEmail().isBlank()) {
            // Only possible for a booking made before passengers were asked for.
            log.warn("No address on the event for {} — nothing sent", event.requestId());
            return Sent.NO_ADDRESS;
        }

        notifier.send(writer.write(event, event.passengerEmail()));

        try {
            sentMailRepo.saveAndFlush(new SentMail(event.userId(), event.requestId(), type));
        } catch (DataIntegrityViolationException e) {
            return Sent.ALREADY_SENT;   // someone got there between the check and here
        }
        return Sent.SENT;
    }
}
