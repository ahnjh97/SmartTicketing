package smartticketing.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import smartticketing.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

public interface MovieRepository extends JpaRepository<Movie, Long> {
    boolean existsByTmdbMovieId(Long tmdbMovieId);
    Optional<Movie> findByTmdbMovieId(Long tmdbMovieId);
    List<Movie> findByTmdbMovieIdIn(List<Long> tmdbMovieIds);
    List<Movie> findTop10ByReleaseDateIsNotNullOrderByReleaseDateDescTmdbMovieIdAsc();
    List<Movie> findTop10ByReleaseDateIsNotNullAndTmdbMovieIdNotInOrderByReleaseDateDescTmdbMovieIdAsc(List<Long> excludedIds);
    List<Movie> findByActiveTrueOrderByIdAsc();

    List<Movie> findTop10ByActiveTrueAndReleaseDateLessThanEqualOrderByAudienceCountDescReleaseDateDesc(LocalDate baseDate);
    List<Movie> findTop10ByActiveTrueAndReleaseDateAfterOrderByReleaseDateAscTitleAsc(LocalDate baseDate);

    @Query("select coalesce(sum(m.audienceCount), 0) from Movie m where m.active = true")
    long sumActiveAudienceCount();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Movie m where m.tmdbMovieId = :tmdbId")
    Optional<Movie> findForMetadataUpdate(Long tmdbId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Movie m SET m.audienceCount = m.audienceCount + :delta WHERE m.id = :movieId AND m.audienceCount + :delta >= 0")
    int updateAudienceCount(@Param("movieId") Long movieId, @Param("delta") int delta);
}
