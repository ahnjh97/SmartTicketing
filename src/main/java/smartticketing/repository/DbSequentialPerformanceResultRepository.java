package smartticketing.repository;

import smartticketing.entity.DbSequentialPerformanceResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DbSequentialPerformanceResultRepository
        extends JpaRepository<DbSequentialPerformanceResult, Long> {
}
