package smartticketing.service;

import org.junit.jupiter.api.Test;
import smartticketing.entity.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class CinemaDayBookingTests {
    @Test void selectedAndSmartBookingsAcceptDawnOnPreviousDayOnly() {
        var date = LocalDate.of(2026, 10, 3);
        var movie = new Movie(); movie.setId(1L);
        var show = new Showtime(); show.setId(2L); show.setMovie(movie);
        show.setStartTime(date.plusDays(1).atTime(1, 0));
        var group = new BookingRequestGroup(); group.setMovie(movie); group.setViewingDate(date);
        group.setSelectedShowtime(show);
        assertThatCode(() -> BookingHoldService.validateGroupShow(group, show)).doesNotThrowAnyException();
        group.setViewingDate(date.plusDays(1));
        assertThatThrownBy(() -> BookingHoldService.validateGroupShow(group, show)).isInstanceOf(BookingRejection.class);
        group.setViewingDate(date); group.setSelectedShowtime(null);
        group.setStartTimeFrom(LocalTime.of(21, 0)); group.setStartTimeTo(LocalTime.of(2, 0));
        assertThatCode(() -> BookingHoldService.validateGroupShow(group, show)).doesNotThrowAnyException();
        group.setStartTimeFrom(LocalTime.MIDNIGHT);
        assertThatCode(() -> BookingHoldService.validateGroupShow(group, show)).doesNotThrowAnyException();
        show.setStartTime(date.plusDays(1).atTime(2, 0));
        assertThatCode(() -> BookingHoldService.validateGroupShow(group, show)).doesNotThrowAnyException();
        show.setStartTime(date.plusDays(1).atTime(2, 1));
        assertThatThrownBy(() -> BookingHoldService.validateGroupShow(group, show)).isInstanceOf(BookingRejection.class);
    }
}
