package smartticketing.repository;

import smartticketing.entity.BookingOperation;
import smartticketing.entity.enums.BookingOperationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingOperationRepository extends JpaRepository<BookingOperation, Long> {
    Optional<BookingOperation> findByUserIdAndOperationTypeAndRequestKey(
            Long userId, BookingOperationType operationType, String requestKey);
}
