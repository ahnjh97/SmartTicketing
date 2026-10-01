package smartticketing.repository;

import smartticketing.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByActiveTrueAndLatitudeIsNotNullAndLongitudeIsNotNullOrderByNameAsc();

    Optional<Theater> findByKakaoPlaceId(String kakaoPlaceId);
}