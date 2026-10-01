package smartticketing.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import smartticketing.dto.movie.MainChartResponseDto;
import smartticketing.service.MainService;

@RestController
@RequestMapping("/api/main")
@RequiredArgsConstructor
public class MainController {

    private final MainService mainService;

    // 메인 차트: 상영중 10편 + 상영예정 10편
    @GetMapping
    public ResponseEntity<MainChartResponseDto> getMainChart() {
        return ResponseEntity.ok(mainService.getMainChart());
    }
}