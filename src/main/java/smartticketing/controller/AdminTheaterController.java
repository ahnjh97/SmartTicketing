package smartticketing.controller;

import smartticketing.service.SeoulTheaterCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/admin/theaters")
public class AdminTheaterController {

    private final SeoulTheaterCollectionService collectionService;

    public AdminTheaterController(
            SeoulTheaterCollectionService collectionService
    ) {
        this.collectionService = collectionService;
    }

    @PostMapping("/collect-seoul")
    public ResponseEntity<?> collectSeoulTheaters(
            @RequestParam(defaultValue = "false") boolean refresh
    ) {

        return ResponseEntity.ok(collectionService.collectSeoulTheaters(refresh));
    }
}
