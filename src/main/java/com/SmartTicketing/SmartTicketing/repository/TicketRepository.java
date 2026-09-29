package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Ticket;
import com.SmartTicketing.SmartTicketing.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    Optional<Ticket> findByReservationId(Long reservationId);
    Optional<Ticket> findByTicketNumber(String ticketNumber);
    List<Ticket> findByReservationUserIdOrderByCreatedAtDesc(Long userId);
    List<Ticket> findByStatus(TicketStatus status);
}
