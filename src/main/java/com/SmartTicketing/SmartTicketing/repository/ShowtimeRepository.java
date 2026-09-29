package com.SmartTicketing.SmartTicketing.repository;

import com.SmartTicketing.SmartTicketing.entity.Showtime;
import com.SmartTicketing.SmartTicketing.entity.enums.ShowtimeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    List<Showtime> findByMovieIdAndStatusAndStartTimeAfterOrderByStartTimeAsc(
            Long movieId,
            ShowtimeStatus status,
            LocalDateTime startTime
    );

    List<Showtime> findByScreenIdAndStatusAndStartTimeAfterOrderByStartTimeAsc(
            Long screenId,
            ShowtimeStatus status,
            LocalDateTime startTime
    );
}