package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.UserPreferredSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserPreferredSeatRepository extends JpaRepository<UserPreferredSeat, Long> {
    List<UserPreferredSeat> findByUserId(Long userId);
    void deleteAllByUserId(Long userId);
}
