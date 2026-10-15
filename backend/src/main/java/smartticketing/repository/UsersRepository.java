package smartticketing.repository;

import smartticketing.entity.Users;
import smartticketing.entity.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsersRepository extends JpaRepository<Users, Long> {
    Optional<Users> findByLoginId(String loginId);
    Optional<Users> findByEmail(String email);
    Optional<Users> findByEmailIgnoreCase(String email);
    Optional<Users> findByNameAndLoginId(String name, String loginId);
    List<Users> findAllByNameAndStatusOrderByIdAsc(String name, UserStatus status);
    boolean existsByLoginId(String loginId);
    boolean existsByLoginIdAndIdNot(String loginId, Long id);
    boolean existsByEmail(String email);
}
