package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.CatalogResponse.*;
import smartticketing.entity.Movie;
import smartticketing.entity.Theater;

@Service
@Transactional(readOnly = true)
public class BookingCatalogService {
    private final EntityManager em;

    public BookingCatalogService(EntityManager em) { this.em = em; }

    public Page<MovieItem> movies(int page, int size) {
        return movies(page, size, false);
    }

    public Page<MovieItem> movies(int page, int size, boolean landscapeOnly) {
        int offset = offset(page, size);
        String where = " where m.active = true" + (landscapeOnly
                ? " and m.backdropUrl is not null and trim(m.backdropUrl) <> ''" : "");
        var items = em.createQuery("from Movie m" + where + " order by m.id", Movie.class)
                .setFirstResult(offset).setMaxResults(size).getResultList().stream().map(MovieItem::from).toList();
        long total = em.createQuery("select count(m) from Movie m" + where, Long.class).getSingleResult();
        return new Page<>(items, page, size, total);
    }

    public MovieDetail movie(Long id) { return MovieDetail.from(requireMovie(id)); }

    public Page<TheaterItem> theaters(String query, int page, int size) {
        return theaters(query, page, size, null);
    }

    public Page<TheaterItem> theaters(String query, int page, int size, smartticketing.entity.enums.TheaterBrand brand) {
        int offset = offset(page, size);
        String term = query == null ? "" : query.trim();
        if (term.length() > 100) throw new IllegalArgumentException("검색어는 100자 이하여야 합니다.");
        // 와일드카드 자체를 검색할 수 있게 이스케이프한다.
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " where t.active = true and (t.name like :pattern escape '!' or t.address like :pattern escape '!')";
        if (brand != null) where += " and t.brand=:brand";
        var rows = em.createQuery("from Theater t" + where + " order by t.name, t.id", Theater.class).setParameter("pattern", pattern);
        var count = em.createQuery("select count(t) from Theater t" + where, Long.class).setParameter("pattern", pattern);
        if (brand != null) { rows.setParameter("brand", brand); count.setParameter("brand", brand); }
        var items = rows.setFirstResult(offset).setMaxResults(size).getResultList().stream().map(TheaterItem::from).toList();
        long total = count.getSingleResult();
        return new Page<>(items, page, size, total);
    }

    public TheaterItem theater(Long id) { return TheaterItem.from(requireTheater(id)); }

    Movie requireMovie(Long id) {
        positiveId(id);
        var movie = em.find(Movie.class, id);
        if (movie == null || !movie.isActive()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "영화를 찾을 수 없습니다.");
        return movie;
    }

    Theater requireTheater(Long id) {
        positiveId(id);
        var theater = em.find(Theater.class, id);
        if (theater == null || !theater.isActive()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "극장을 찾을 수 없습니다.");
        return theater;
    }

    static void positiveId(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("ID는 양수여야 합니다.");
    }

    private static int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("page는 0 이상, size는 1~100이어야 합니다.");
        return page * size;
    }
}
