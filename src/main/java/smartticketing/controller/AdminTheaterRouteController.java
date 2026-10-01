package smartticketing.controller;

import smartticketing.service.SeoulTheaterRouteCollectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/theaters")
public class AdminTheaterRouteController {

    private final SeoulTheaterRouteCollectionService routeCollectionService;
    private final String adminKey;

    public AdminTheaterRouteController(
            SeoulTheaterRouteCollectionService routeCollectionService,
            @Value("${app.admin.key:}") String adminKey
    ) {
        this.routeCollectionService = routeCollectionService;
        this.adminKey = adminKey;
    }

    @PostMapping("/collect-seoul-routes")
    public ResponseEntity<?> collectSeoulRoutes(
            @RequestHeader(value = "X-Admin-Key", required = false) String requestKey,
            @RequestParam(defaultValue = "5.0") double gridStepKm,
            @RequestParam(defaultValue = "1") int maxGridPoints
    ) {
        if (adminKey.isBlank() || requestKey == null || !adminKey.equals(requestKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "관리자 키가 올바르지 않습니다."));
        }

        return ResponseEntity.ok(
                routeCollectionService.collectSeoulRoutes(
                        gridStepKm,
                        maxGridPoints
                )
        );
    }
}
