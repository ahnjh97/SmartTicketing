package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.BookingRequestGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRequestGroupRepository extends JpaRepository<BookingRequestGroup, Long> {
    Optional<BookingRequestGroup> findByIdAndUserId(Long id, Long userId);
}
