package smartticketing.repository;

import smartticketing.entity.WaitingQueue;
import smartticketing.entity.enums.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueue, Long> {
    Optional<WaitingQueue> findFirstByShowtimeIdAndStatusOrderByQueueNumberAsc(Long showtimeId, QueueStatus status);
}
