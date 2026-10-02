package smartticketing.repository;

import smartticketing.entity.Ticket;
import smartticketing.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    Optional<Ticket> findByReservationId(Long reservationId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from Ticket t where t.reservation.id = :id")
    Optional<Ticket> findLockedByReservationId(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<Ticket> findByTicketNumber(String ticketNumber);
    List<Ticket> findByReservationUserIdOrderByCreatedAtDesc(Long userId);
    List<Ticket> findByStatus(TicketStatus status);
}
