package smartticketing.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import smartticketing.dto.movie.MovieChartResponseDto;
import smartticketing.service.MainService;

import java.util.List;

@RestController
@RequestMapping("/api/main")
@RequiredArgsConstructor
public class MainController {
    private final MainService mainService;

    @GetMapping
    public ResponseEntity<List<MovieChartResponseDto>> getMainChart() {
        return ResponseEntity.ok(mainService.getMainChartMovies());
    }
}
