package smartticketing.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.ShowtimeResponse.*;
import smartticketing.service.ShowtimeQueryService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ShowtimeQueryController {
    private final ShowtimeQueryService query;
    public ShowtimeQueryController(ShowtimeQueryService query) { this.query = query; }

    @GetMapping("/showtimes/availability")
    public ScheduleAvailability availability(@RequestParam Long movieId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) List<Long> theaterIds) {
        return query.availability(movieId, date, theaterIds);
    }

    @GetMapping("/showtimes")
    public Items<ShowtimeItem> showtimes(@RequestParam(required = false) Long movieId,
            @RequestParam(required = false) Long theaterId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startUntil) {
        return query.showtimes(movieId, theaterId, date, startFrom, startUntil);
    }

    @GetMapping("/theaters/{id}/movies")
    public Items<MovieItem> movies(@PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return query.theaterMovies(id, date);
    }

    @GetMapping("/showtimes/{id}/seats")
    public SeatMap seats(@PathVariable Long id) { return query.seats(id); }
}
