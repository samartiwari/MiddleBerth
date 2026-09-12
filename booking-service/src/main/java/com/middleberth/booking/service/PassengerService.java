package com.middleberth.booking.service;

import com.middleberth.booking.domain.Passenger;
import com.middleberth.booking.dto.PassengerDetails;
import com.middleberth.booking.exception.PassengerNotFoundException;
import com.middleberth.booking.exception.PassengerExistsException;
import com.middleberth.booking.repository.PassengerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The master list.
 *
 * Ordinary CRUD, and deliberately nothing more: it is read and edited long before
 * 10:00:00, so the booking path never touches it. The client loads the list, the
 * user picks somebody, and the booking request carries their details inline.
 *
 * Every method takes the user id and filters by it. A passenger belongs to one
 * account and is invisible to every other.
 */
@Service
@RequiredArgsConstructor
public class PassengerService {

    private final PassengerRepository passengerRepo;

    public List<PassengerDetails> list(Long userId) {
        return passengerRepo.findByUserIdOrderByNameAsc(userId).stream()
                .map(p -> new PassengerDetails(p.getName(), p.getEmail(), p.getPhone()))
                .toList();
    }

    public List<Passenger> listSaved(Long userId) {
        return passengerRepo.findByUserIdOrderByNameAsc(userId);
    }

    /**
     * The UNIQUE constraint decides whether this person is already on the list,
     * not a lookup first — check-then-act is never safe on its own, the same
     * lesson as duplicate booking request ids.
     */
    public Passenger add(Long userId, PassengerDetails details) {
        try {
            return passengerRepo.saveAndFlush(
                    new Passenger(userId, details.name(), details.email(), details.phone()));
        } catch (DataIntegrityViolationException e) {
            throw new PassengerExistsException(details.name());
        }
    }

    @Transactional
    public void remove(Long userId, Long passengerId) {
        if (passengerRepo.deleteByIdAndUserId(passengerId, userId) == 0) {
            // Either it never existed or it is not theirs. Both answer the same way,
            // so nobody can discover other people's ids by the error they get.
            throw new PassengerNotFoundException(passengerId);
        }
    }
}
