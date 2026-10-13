package smartticketing.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import smartticketing.entity.EmailChallenge;
import java.util.Optional;

public interface EmailChallengeRepository extends JpaRepository<EmailChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailChallenge c where c.id = :id")
    Optional<EmailChallenge> lockById(@Param("id") String id);
}
