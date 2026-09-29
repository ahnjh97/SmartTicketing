package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.UserPreferredSeat;
import com.SmartTicketing.SmartTicketing.entity.enums.SeatPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPreferredSeatRepository
        extends JpaRepository<UserPreferredSeat, Long> {

    List<UserPreferredSeat> findByUserId(Long userId);

    boolean existsByUserIdAndSeatPosition(
            Long userId,
            SeatPosition seatPosition
    );
}