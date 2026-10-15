package smartticketing.repository;

import smartticketing.entity.UserNearbyTheater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNearbyTheaterRepository extends JpaRepository<UserNearbyTheater, Long> {
    List<UserNearbyTheater> findByUserIdOrderByPriorityAsc(Long userId);
    void deleteAllByUserId(Long userId);
}
