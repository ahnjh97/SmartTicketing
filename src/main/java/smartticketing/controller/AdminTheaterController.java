package smartticketing.controller;

import smartticketing.service.SeoulTheaterCollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/admin/theaters")
public class AdminTheaterController {

    private final SeoulTheaterCollectionService collectionService;
    private final smartticketing.service.AdminTaskService tasks;

    public AdminTheaterController(
            SeoulTheaterCollectionService collectionService, smartticketing.service.AdminTaskService tasks
    ) {
        this.collectionService = collectionService;
        this.tasks = tasks;
    }

    @PostMapping("/collect-seoul")
    public ResponseEntity<?> collectSeoulTheaters(
            @RequestParam(defaultValue = "false") boolean refresh
    ) {

        return ResponseEntity.ok(tasks.submit("영화관 수집", task -> task.result(collectionService.collectSeoulTheaters(refresh))));
    }
}
