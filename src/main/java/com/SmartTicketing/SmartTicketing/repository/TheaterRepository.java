package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByActiveTrueOrderByNameAsc();

    Optional<Theater> findByKakaoPlaceId(String kakaoPlaceId);
}