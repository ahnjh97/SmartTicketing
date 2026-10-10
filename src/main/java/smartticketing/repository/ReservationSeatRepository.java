package smartticketing.repository;

import smartticketing.entity.ReservationSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {
    List<ReservationSeat> findByReservationId(Long reservationId);
    @org.springframework.data.jpa.repository.Query("select rs from ReservationSeat rs join fetch rs.seat where rs.reservation.id in :ids order by rs.seat.seatRow,rs.seat.seatNumber")
    List<ReservationSeat> findWithSeatsByReservationIds(@org.springframework.data.repository.query.Param("ids") List<Long> ids);
}
