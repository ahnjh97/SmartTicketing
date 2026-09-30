package smartticketing.repository;

import smartticketing.entity.UserPreferredSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPreferredSeatRepository
        extends JpaRepository<UserPreferredSeat, Long> {

    List<UserPreferredSeat> findByUserIdOrderByPriorityAsc(Long userId);

    void deleteAllByUserId(Long userId);
}