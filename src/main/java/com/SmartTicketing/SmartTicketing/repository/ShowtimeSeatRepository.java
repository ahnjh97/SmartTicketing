package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.ShowtimeSeat;
import com.SmartTicketing.SmartTicketing.entity.enums.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShowtimeSeatRepository
        extends JpaRepository<ShowtimeSeat, Long> {

    List<ShowtimeSeat> findByShowtimeId(Long showtimeId);

    List<ShowtimeSeat> findByShowtimeIdAndStatus(
            Long showtimeId,
            SeatStatus status
    );

    Optional<ShowtimeSeat> findByShowtimeIdAndSeatId(
            Long showtimeId,
            Long seatId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ss
            from ShowtimeSeat ss
            where ss.showtime.id = :showtimeId
              and ss.seat.id = :seatId
            """)
    Optional<ShowtimeSeat> findByShowtimeIdAndSeatIdForUpdate(
            @Param("showtimeId") Long showtimeId,
            @Param("seatId") Long seatId
    );
}