package smartticketing.repository;

import smartticketing.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Reservation r where r.id = :id")
    java.util.Optional<Reservation> findLockedById(@org.springframework.data.repository.query.Param("id") Long id);
}
