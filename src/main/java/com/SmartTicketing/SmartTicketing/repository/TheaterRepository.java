package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Theater;
import com.SmartTicketing.SmartTicketing.entity.enums.TheaterBrand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByBrandAndActiveTrue(TheaterBrand brand);

    List<Theater> findByActiveTrue();
}