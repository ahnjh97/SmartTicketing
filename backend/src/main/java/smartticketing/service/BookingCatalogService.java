package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import smartticketing.dto.booking.CatalogResponse.*;
import smartticketing.entity.Movie;
import smartticketing.entity.Theater;

@Service
@Transactional(readOnly = true)
public class BookingCatalogService {
    private final EntityManager em;
    private final RedisQueryCache queryCache;

    public BookingCatalogService(EntityManager em, RedisQueryCache queryCache) {
        this.em = em;
        this.queryCache = queryCache;
    }

    public Page<MovieItem> movies(int page, int size) {
        return movies(page, size, false);
    }

    public Page<MovieItem> movies(int page, int size, boolean landscapeOnly) {
        int offset = offset(page, size);
        String cacheKey = page + ":" + size + ":" + landscapeOnly;
        Page<MovieItem> cached = queryCache.get("movies", cacheKey, new TypeReference<Page<MovieItem>>() {});
        if (cached != null) return cached;
        String where = " where m.active = true" + (landscapeOnly
                ? " and m.backdropUrl is not null and trim(m.backdropUrl) <> ''" : "");
        var items = em.createQuery("""
                select new smartticketing.dto.booking.CatalogResponse$MovieItem(
                    m.id, m.title, m.posterUrl, m.runningTime, m.rating, m.backdropUrl, m.logoUrl)
                from Movie m
                """ + where + " order by m.id", MovieItem.class)
                .setFirstResult(offset).setMaxResults(size).getResultList();
        long total = em.createQuery("select count(m) from Movie m" + where, Long.class).getSingleResult();
        Page<MovieItem> response = new Page<>(items, page, size, total);
        queryCache.put("movies", cacheKey, response);
        return response;
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
        String cacheKey = term + ":" + page + ":" + size + ":" + (brand == null ? "" : brand.name());
        Page<TheaterItem> cached = queryCache.get("theaters", cacheKey, new TypeReference<Page<TheaterItem>>() {});
        if (cached != null) return cached;
        String where = " where t.active = true";
        if (!term.isEmpty()) where += " and (t.name like :pattern escape '!' or t.address like :pattern escape '!')";
        if (brand != null) where += " and t.brand=:brand";
        var rows = em.createQuery("""
                select new smartticketing.dto.booking.CatalogResponse$TheaterItem(
                    t.id, t.name, t.brand, t.address, t.latitude, t.longitude)
                from Theater t
                """ + where + " order by t.name, t.id", TheaterItem.class);
        var count = em.createQuery("select count(t) from Theater t" + where, Long.class);
        if (!term.isEmpty()) { rows.setParameter("pattern", pattern); count.setParameter("pattern", pattern); }
        if (brand != null) { rows.setParameter("brand", brand); count.setParameter("brand", brand); }
        var items = rows.setFirstResult(offset).setMaxResults(size).getResultList();
        long total = count.getSingleResult();
        Page<TheaterItem> response = new Page<>(items, page, size, total);
        queryCache.put("theaters", cacheKey, response);
        return response;
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
