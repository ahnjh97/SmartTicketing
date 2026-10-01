package smartticketing.repository;

import smartticketing.entity.DbParallelPerformanceResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DbParallelPerformanceResultRepository
        extends JpaRepository<DbParallelPerformanceResult, Long> {
}
