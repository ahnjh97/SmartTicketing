package smartticketing.controller;

import org.springframework.web.bind.annotation.*;
import smartticketing.dto.booking.CatalogResponse.*;
import smartticketing.service.BookingCatalogService;

@RestController
@RequestMapping("/api")
public class BookingCatalogController {
    private final BookingCatalogService catalog;
    public BookingCatalogController(BookingCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/movies")
    public Page<MovieItem> movies(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean landscapeOnly) {
        return landscapeOnly ? catalog.movies(page, size, true) : catalog.movies(page, size);
    }

    @GetMapping("/movies/{id}")
    public MovieDetail movie(@PathVariable Long id) { return catalog.movie(id); }

    @GetMapping("/theaters")
    public Page<TheaterItem> theaters(@RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) smartticketing.entity.enums.TheaterBrand brand) {
        return brand == null ? catalog.theaters(query, page, size) : catalog.theaters(query, page, size, brand);
    }

    @GetMapping("/theaters/{id}")
    public TheaterItem theater(@PathVariable Long id) { return catalog.theater(id); }
}
