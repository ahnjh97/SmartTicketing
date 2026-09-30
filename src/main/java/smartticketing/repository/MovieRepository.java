package smartticketing.repository;

import smartticketing.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    boolean existsByTmdbMovieId(Long tmdbMovieId);
    Optional<Movie> findByTmdbMovieId(Long tmdbMovieId);
    List<Movie> findByActiveTrueOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Movie m where m.tmdbMovieId = :tmdbId")
    Optional<Movie> findForMetadataUpdate(Long tmdbId);
}
