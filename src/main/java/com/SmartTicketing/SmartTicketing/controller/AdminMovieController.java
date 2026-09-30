package com.SmartTicketing.SmartTicketing.controller;

import com.SmartTicketing.SmartTicketing.dto.movie.MovieImportResult;
import com.SmartTicketing.SmartTicketing.service.MovieImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    // 설정 파일의 영화 목록 중 DB에 없는 영화만 TMDB에서 가져와 저장
    @PostMapping("/import")
    public ResponseEntity<?> importMovies(
            @RequestHeader(value = "X-Admin-Key", required = false) String requestKey) {

        if (requestKey == null || !requestKey.equals(adminKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "관리자 키가 올바르지 않습니다."));
        }

        MovieImportResult result = movieImportService.importConfiguredMovies();
        return ResponseEntity.ok(result);
    }
}