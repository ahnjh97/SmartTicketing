package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Reservation;
import com.SmartTicketing.SmartTicketing.entity.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Reservation> findByShowtimeIdAndStatus(
            Long showtimeId,
            ReservationStatus status
    );

    Optional<Reservation> findByWaitingQueueId(Long waitingQueueId);
}