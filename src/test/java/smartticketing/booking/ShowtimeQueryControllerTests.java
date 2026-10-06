package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import smartticketing.controller.ShowtimeQueryController;
import smartticketing.dto.booking.ShowtimeResponse.*;
import smartticketing.entity.enums.*;
import smartticketing.exception.ApiExceptionHandler;
import smartticketing.service.ShowtimeQueryService;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShowtimeQueryControllerTests {
    private ShowtimeQueryService query;
    private MockMvc mvc;
    @BeforeEach void setup() {
        query = mock(ShowtimeQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ShowtimeQueryController(query))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void datesAreRequiredAndMalformedDatesReturnConsistentErrors() throws Exception {
        mvc.perform(get("/api/theaters/1/movies")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/theaters/1/movies").param("date", "2026-02-30"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(query);
    }

    @Test void availabilityBindsOptionalTheaterScopeAndRequiresMovieAndDate() throws Exception {
        var date = LocalDate.of(2026, 10, 1);
        var start = OffsetDateTime.parse("2026-10-01T22:00:00+09:00");
        when(query.availability(1L, date, List.of(2L, 3L)))
                .thenReturn(new ScheduleAvailability(true, start, start, start.minusHours(3)));
        mvc.perform(get("/api/showtimes/availability").param("movieId", "1").param("date", date.toString()).param("theaterIds", "2,3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.latestStartTime").value("2026-10-01T22:00:00+09:00"));
        verify(query).availability(1L, date, List.of(2L, 3L));
        mvc.perform(get("/api/showtimes/availability").param("movieId", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/showtimes/availability").param("date", date.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/showtimes/availability").param("movieId", "1").param("date", "2026-02-30")).andExpect(status().isBadRequest());
    }

    @Test void timeFiltersBindWithoutLosingNextDayDisplayInformation() throws Exception {
        var start = OffsetDateTime.parse("2026-10-01T22:00:00+09:00");
        var item = new ShowtimeItem(9L, 1L, 2L, 3L, "1관", start, start.plusHours(3), true,
                10000, 108, 63, 1, true, ShowtimeStatus.SCHEDULED, List.of(1));
        when(query.showtimes(1L, null, LocalDate.of(2026, 10, 1), LocalTime.of(22, 0), LocalTime.of(2, 0)))
                .thenReturn(new Items<>(List.of(item), start.minusHours(5)));
        mvc.perform(get("/api/showtimes").param("movieId", "1").param("date", "2026-10-01")
                        .param("startFrom", "22:00").param("startUntil", "02:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].endsNextDay").value(true))
                .andExpect(jsonPath("$.items[0].endTime").value("2026-10-02T01:00:00+09:00"));
        mvc.perform(get("/api/showtimes").param("movieId", "1").param("date", "2026-10-01")
                .param("startFrom", "25:00").param("startUntil", "02:00"))
                .andExpect(status().isBadRequest());
    }

    @Test void seatResponsePreservesOffsetAndDoesNotExposeHoldOwnerOrReservation() throws Exception {
        var seat = new SeatItem(31L, "A", 1, "left", 1, SeatPosition.SIDE_FRONT, SeatStatus.HOLDING);
        when(query.seats(9L)).thenReturn(new SeatMap(9L, List.of(seat), 1, 0, 0, true,
                OffsetDateTime.parse("2026-10-01T09:00:00+09:00")));
        mvc.perform(get("/api/showtimes/9/seats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value("2026-10-01T09:00:00+09:00"))
                .andExpect(jsonPath("$.seats[0].id").value(31))
                .andExpect(jsonPath("$.seats[0].status").value("HOLDING"))
                .andExpect(jsonPath("$.seats[0].reservation").doesNotExist())
                .andExpect(jsonPath("$.seats[0].reservationId").doesNotExist())
                .andExpect(jsonPath("$.seats[0].holdExpiredAt").doesNotExist());
    }
}
