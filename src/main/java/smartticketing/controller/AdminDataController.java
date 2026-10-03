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
    private final AdminTaskService tasks;

    public AdminDataController(AdminDataService data, ShowtimeScheduleSeedService schedule,
                               ShowtimeInventoryService inventory, MovieImportService movies, SeoulTheaterCollectionService theaters, AdminTaskService tasks) {
        this.data = data; this.schedule = schedule; this.inventory = inventory; this.movies = movies; this.theaters = theaters; this.tasks = tasks;
    }

    @GetMapping("/task") public AdminTaskService.Status task(@RequestParam(required=false) String id) { return tasks.status(id); }

    @GetMapping("/collection-status")
    public List<CollectionCount> collectionStatus() { return data.collectionStatus(); }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return data.summary();
    }
    @GetMapping("/browse")
    public Map<String, Object> browse(@RequestParam(required=false) Long theaterId, @RequestParam(required=false) Long movieId) {
        return data.browse(theaterId, movieId);
    }
    @GetMapping("/dates")
    public List<Map<String, Object>> dates(@RequestParam long theaterId, @RequestParam long movieId) {
        return data.dates(theaterId, movieId);
    }
    @GetMapping("/{kind}")
    public Map<String, Object> list(@PathVariable String kind, @RequestParam(defaultValue="") String search,
            @RequestParam(required=false) Long movieId, @RequestParam(required=false) Long theaterId,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page, @RequestParam(required=false) String movieStatus) {
        return data.list(new Scope(kind, "filtered", null, search, movieId, theaterId, date, movieStatus), page);
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
    public AdminTaskService.Status delete(@RequestBody DeleteRequest request) {
        return tasks.delete(request);
    }
    @PostMapping("/prepare-schedule")
    public AdminTaskService.Status prepare() {
        return tasks.submit("시간표·좌석 보충", task -> {
        long shows = 0, screens = 0, seats = 0;
        var plan = schedule.preparePlan();
        var ids = schedule.findActiveTheaterIds(); int completed = 0;
        for (long id : ids) {
            task.progress(completed, ids.size(), "영화관 #" + id + " 시간표 생성 중");
            var r = schedule.seedTheater(id, plan); shows += r.createdShowtimes(); screens += r.createdScreens();
            completed++;
        }
        var pending = inventory.pendingScreenIds(); completed = 0;
        for (long id : pending) {
            task.progress(completed, pending.size(), "상영관 #" + id + " 좌석 보충 중");
            seats += inventory.prepare(id).createdShowtimeSeats(); completed++;
        }
        task.progress(completed, pending.size(), "좌석 보충 완료");
        task.result(Map.of("showtimes", shows, "screens", screens, "showtime_seats", seats));
        });
    }

    @PostMapping("/collect-movies")
    public AdminTaskService.Status collectMovies(@RequestParam(defaultValue="false") boolean refresh) {
        return tasks.submit("영화 수집", task -> { task.progress(0, 0, "외부 영화 정보 수집 중"); task.result(movies.importConfiguredMovies(refresh)); });
    }
    @PostMapping("/collect-theaters")
    public AdminTaskService.Status collectTheaters(@RequestParam(defaultValue="false") boolean refresh) {
        return tasks.submit("영화관 수집", task -> { task.progress(0, 0, "외부 영화관 정보 수집 중"); task.result(theaters.collectSeoulTheaters(refresh)); });
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> databaseConflict(org.springframework.dao.DataAccessException e) {
        log.warn("관리자 데이터 작업 실패: {}", e.getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "연결 데이터 또는 동시 작업으로 처리하지 못했습니다. 새로 조회한 뒤 다시 시도해주세요."));
    }
}
