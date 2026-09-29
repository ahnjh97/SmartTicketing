package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.WaitingQueue;
import com.SmartTicketing.SmartTicketing.entity.enums.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaitingQueueRepository
        extends JpaRepository<WaitingQueue, Long> {

    List<WaitingQueue> findByShowtimeIdOrderByQueueNumberAsc(
            Long showtimeId
    );

    List<WaitingQueue> findByUserIdOrderByCreatedAtDesc(
            Long userId
    );

    Optional<WaitingQueue> findFirstByShowtimeIdAndStatusOrderByQueueNumberAsc(
            Long showtimeId,
            QueueStatus status
    );
}