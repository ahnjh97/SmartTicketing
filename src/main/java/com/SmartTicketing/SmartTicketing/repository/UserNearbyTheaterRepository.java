package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.UserNearbyTheater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNearbyTheaterRepository
        extends JpaRepository<UserNearbyTheater, Long> {

    List<UserNearbyTheater> findByUserIdOrderByPriorityAsc(Long userId);
}