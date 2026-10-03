package smartticketing.repository;

import smartticketing.entity.Ticket;
import smartticketing.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    Optional<Ticket> findByReservationId(Long reservationId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.reservation.id = :id")
    Optional<Ticket> findLockedByReservationId(@Param("id") Long id);
    Optional<Ticket> findByTicketNumber(String ticketNumber);
    Optional<Ticket> findByQrCode(String qrCode);
    @Modifying
    @Query("update Ticket t set t.status = :used, t.updatedAt = :updatedAt where t.qrCode = :qrCode and t.status = :valid")
    int markUsedIfValid(@Param("qrCode") String qrCode, @Param("valid") TicketStatus valid, @Param("used") TicketStatus used, @Param("updatedAt") LocalDateTime updatedAt);
    List<Ticket> findByReservationUserIdOrderByCreatedAtDesc(Long userId);
    List<Ticket> findByStatus(TicketStatus status);
}
