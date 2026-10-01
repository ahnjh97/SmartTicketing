package smartticketing.controller;

import smartticketing.dto.theater.NearbyTheaterResponse;
import smartticketing.service.KakaoMapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/theaters")
public class TheaterController {

    private final KakaoMapService kakaoMapService;

    public TheaterController(
            KakaoMapService kakaoMapService
    ) {
        this.kakaoMapService = kakaoMapService;
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<NearbyTheaterResponse>> nearby(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(required = false) String address,
            @RequestParam(defaultValue = "DISTANCE") String sort
    ) {
        return ResponseEntity.ok(
                kakaoMapService.findNearbyTheaters(
                        address,
                        latitude,
                        longitude,
                        10_000,
                        sort
                )
        );
    }
}
