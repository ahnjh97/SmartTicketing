package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.UserPreferredTheater;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserPreferredTheaterRepository extends JpaRepository<UserPreferredTheater, Long> {
    List<UserPreferredTheater> findByUserIdOrderByPriorityAsc(Long userId);
    void deleteAllByUserId(Long userId);
}
