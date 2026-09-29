package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.QueueCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QueueCounterRepository
        extends JpaRepository<QueueCounter, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select qc
            from QueueCounter qc
            where qc.showtimeId = :showtimeId
            """)
    Optional<QueueCounter> findByShowtimeIdForUpdate(
            @Param("showtimeId") Long showtimeId
    );
}