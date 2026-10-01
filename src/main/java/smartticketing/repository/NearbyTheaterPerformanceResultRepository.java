package smartticketing.repository;

import smartticketing.entity.NearbyTheaterPerformanceResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NearbyTheaterPerformanceResultRepository
        extends JpaRepository<NearbyTheaterPerformanceResult, Long> {
}
