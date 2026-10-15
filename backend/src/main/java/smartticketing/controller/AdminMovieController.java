package smartticketing.controller;

import smartticketing.dto.movie.MovieImportResult;
import smartticketing.service.MovieImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequestMapping("/api/admin/movies")
public class AdminMovieController {

    private final MovieImportService movieImportService;
    private final smartticketing.service.AdminTaskService tasks;

    public AdminMovieController(
            MovieImportService movieImportService, smartticketing.service.AdminTaskService tasks) {
        this.movieImportService = movieImportService;
        this.tasks = tasks;
    }

    // 없는 영화와 메타데이터를 아직 확인하지 않은 영화만 수집/보완한다.
    @PostMapping("/import")
    public ResponseEntity<?> importMovies(
            @RequestParam(defaultValue = "false") boolean refresh) {


        return ResponseEntity.ok(tasks.submit("영화 수집", task -> task.result(movieImportService.importConfiguredMovies(refresh))));
    }
}
