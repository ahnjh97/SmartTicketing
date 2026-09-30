package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.BookingOperation;
import com.SmartTicketing.SmartTicketing.entity.enums.BookingOperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingOperationRepository extends JpaRepository<BookingOperation, Long> {
    Optional<BookingOperation> findByUserIdAndOperationTypeAndRequestKey(
            Long userId, BookingOperationType operationType, String requestKey);
}
