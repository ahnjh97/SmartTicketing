package smartticketing.repository;

import smartticketing.entity.TheaterRouteCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TheaterRouteCacheRepository extends JpaRepository<TheaterRouteCache, Long> {

    Optional<TheaterRouteCache> findByGridKeyAndTheaterId(
            String gridKey,
            Long theaterId
    );

    List<TheaterRouteCache> findByGridKey(String gridKey);
}
