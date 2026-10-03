package smartticketing.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import smartticketing.service.*;
import smartticketing.service.AdminDataService.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/admin/data")
public class AdminDataController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminDataController.class);
    private final AdminDataService data;
    private final ShowtimeScheduleSeedService schedule;
    private final ShowtimeInventoryService inventory;
    private final MovieImportService movies;
    private final SeoulTheaterCollectionService theaters;

    public AdminDataController(AdminDataService data, ShowtimeScheduleSeedService schedule,
                               ShowtimeInventoryService inventory, MovieImportService movies, SeoulTheaterCollectionService theaters) {
        this.data = data; this.schedule = schedule; this.inventory = inventory; this.movies = movies; this.theaters = theaters;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return data.summary();
    }
    @GetMapping("/browse")
    public Map<String, Object> browse(@RequestParam(required=false) Long theaterId, @RequestParam(required=false) Long movieId) {
        return data.browse(theaterId, movieId);
    }
    @GetMapping("/{kind}")
    public Map<String, Object> list(@PathVariable String kind, @RequestParam(defaultValue="") String search,
            @RequestParam(required=false) Long movieId, @RequestParam(required=false) Long theaterId,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page) {
        return data.list(new Scope(kind, "filtered", null, search, movieId, theaterId, date), page);
    }
    @GetMapping("/showtimes/{id}/seats")
    public List<Map<String, Object>> seats(@PathVariable long id) {
        return data.seats(id);
    }
    @PostMapping("/preview-delete")
    public Preview preview(@RequestBody Scope scope) {
        return data.preview(scope);
    }
    @PostMapping("/delete")
    public Preview delete(@RequestBody DeleteRequest request) {
        var result = data.delete(request);
        log.info("[관리자 데이터 삭제] 종류={} 범위={} 대상={} 영향={}", request.scope().kind(), request.scope().mode(), result.targetCount(), result.counts());
        return result;
    }
    @PatchMapping("/{kind}/{id}")
    public Map<String, Boolean> edit(@PathVariable String kind, @PathVariable long id, @RequestBody Edit edit) {
        data.edit(kind, id, edit); return Map.of("updated", true);
    }
    @PostMapping("/prepare-schedule")
    public Map<String, Long> prepare() {
        long shows = 0, screens = 0, seats = 0;
        for (long id : schedule.findActiveTheaterIds()) {
            var r = schedule.seedTheater(id); shows += r.createdShowtimes(); screens += r.createdScreens();
        }
        for (long id : inventory.screenIds()) seats += inventory.prepare(id).createdShowtimeSeats();
        return Map.of("showtimes", shows, "screens", screens, "showtime_seats", seats);
    }

    @PostMapping("/collect-movies")
    public smartticketing.dto.movie.MovieImportResult collectMovies(@RequestParam(defaultValue="false") boolean refresh) {
        return movies.importConfiguredMovies(refresh);
    }
    @PostMapping("/collect-theaters")
    public Map<String, Object> collectTheaters(@RequestParam(defaultValue="false") boolean refresh) {
        return theaters.collectSeoulTheaters(refresh);
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> databaseConflict(org.springframework.dao.DataAccessException e) {
        log.warn("관리자 데이터 작업 실패: {}", e.getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "연결 데이터 또는 동시 작업으로 처리하지 못했습니다. 새로 조회한 뒤 다시 시도해주세요."));
    }
}
