package smartticketing.controller;

import smartticketing.service.SeoulTheaterCollectionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/theaters")
public class AdminTheaterController {

    private final SeoulTheaterCollectionService collectionService;
    private final String adminKey;

    public AdminTheaterController(
            SeoulTheaterCollectionService collectionService,
            @Value("${app.admin.key:}") String adminKey
    ) {
        this.collectionService = collectionService;
        this.adminKey = adminKey;
    }

    @PostMapping("/collect-seoul")
    public ResponseEntity<?> collectSeoulTheaters(
            @RequestHeader(value = "X-Admin-Key", required = false) String requestKey
    ) {
        if (adminKey.isBlank() || requestKey == null || !adminKey.equals(requestKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "관리자 키가 올바르지 않습니다."));
        }

        return ResponseEntity.ok(collectionService.collectSeoulTheaters());
    }
}
