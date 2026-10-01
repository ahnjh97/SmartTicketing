package smartticketing.repository;

import smartticketing.entity.ApiOriginalPerformanceResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiOriginalPerformanceResultRepository
        extends JpaRepository<ApiOriginalPerformanceResult, Long> {
}
