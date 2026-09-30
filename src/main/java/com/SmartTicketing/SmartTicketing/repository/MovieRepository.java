package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    boolean existsByTmdbMovieId(Long tmdbMovieId);
}
