package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Users;
import com.SmartTicketing.SmartTicketing.entity.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsersRepository extends JpaRepository<Users, Long> {
    Optional<Users> findByLoginId(String loginId);
    Optional<Users> findByEmail(String email);
    Optional<Users> findByEmailIgnoreCase(String email);
    List<Users> findAllByNameAndStatusOrderByIdAsc(String name, UserStatus status);
    boolean existsByLoginId(String loginId);
    boolean existsByLoginIdAndIdNot(String loginId, Long id);
    boolean existsByEmail(String email);
}
