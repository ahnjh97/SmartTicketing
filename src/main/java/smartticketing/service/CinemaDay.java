package smartticketing.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 새벽 04시 이전 회차는 전날 편성에 포함한다. 실제 상영 일시는 변경하지 않는다. */
public final class CinemaDay {
    private static final LocalTime BOUNDARY = LocalTime.of(4, 0);
    private CinemaDay() {}

    public static LocalDate date(LocalDateTime start) {
        return start.minusHours(4).toLocalDate();
    }

    public static LocalDateTime start(LocalDate date) {
        return date.atTime(BOUNDARY);
    }

    public static LocalDateTime time(LocalDate date, LocalTime time) {
        return (time.isBefore(BOUNDARY) ? date.plusDays(1) : date).atTime(time);
    }
}
