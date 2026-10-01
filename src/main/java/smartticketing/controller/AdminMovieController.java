package smartticketing.controller;

import smartticketing.dto.movie.MovieImportResult;
import smartticketing.service.MovieImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/movies")
public class AdminMovieController {

    private final MovieImportService movieImportService;
    private final String adminKey;

    public AdminMovieController(
            MovieImportService movieImportService,
            @Value("${app.admin.key}") String adminKey) {
        this.movieImportService = movieImportService;
        this.adminKey = adminKey;
    }

    // 없는 영화와 메타데이터를 아직 확인하지 않은 영화만 수집/보완한다.
    @PostMapping("/import")
    public ResponseEntity<?> importMovies(
            @RequestHeader(value = "X-Admin-Key", required = false) String requestKey,
            @RequestParam(defaultValue = "false") boolean refresh) {

        if (requestKey == null || !requestKey.equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "관리자 키가 올바르지 않습니다."));
        }

        MovieImportResult result = movieImportService.importConfiguredMovies(refresh);
        return ResponseEntity.ok(result);
    }
}
