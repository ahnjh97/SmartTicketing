package smartticketing.service;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit catalog maintenance. Never disables foreign keys or touches account records. */
@Service
public class AdminDataService {
    private final NamedParameterJdbcTemplate db;
    @org.springframework.beans.factory.annotation.Value("${app.reference-date:}")
    private String referenceDate = "";
    public AdminDataService(DataSource source) {
        db = new NamedParameterJdbcTemplate(source);
        db.getJdbcTemplate().setQueryTimeout(20);
    }

    public record Scope(String kind, String mode, List<Long> ids, String search, Long movieId,
                        Long theaterId, java.time.LocalDate date, String movieStatus) {
        public Scope(String kind, String mode, List<Long> ids, String search, Long movieId, Long theaterId, java.time.LocalDate date) {
            this(kind, mode, ids, search, movieId, theaterId, date, null);
        }
    }
    public record DeleteRequest(Scope scope, String fingerprint, String confirmation, boolean includeBookings) {}
    public record Preview(Map<String, Long> counts, String fingerprint, boolean hasBookings, long targetCount) {}
    private record Filter(String sql, Map<String, Object> params) {}
    private record Plan(Map<String, Object> params, LinkedHashMap<String, String> deletes,
                        List<Long> roots, List<Long> groups, long detachedGroups) {}

    private String table(String kind) {
        if (!Set.of("movies", "theaters", "showtimes").contains(Objects.toString(kind, "")))
            throw new IllegalArgumentException("지원하지 않는 데이터 종류입니다.");
        return kind;
    }

    private Filter filter(Scope s) {
        table(s.kind());
        Map<String, Object> p = new HashMap<>();
        String sql = "1=1";
        if ("movies".equals(s.kind()) && s.movieStatus() != null) {
            p.put("releaseDate", movieReferenceDate());
            sql += switch (s.movieStatus()) {
                case "now" -> " and x.release_date<=:releaseDate";
                case "upcoming" -> " and (x.release_date>:releaseDate or x.release_date is null)";
                default -> throw new IllegalArgumentException("지원하지 않는 영화 분류입니다.");
            };
        }
        if (s.search() != null && !s.search().isBlank()) {
            p.put("search", "%" + s.search().trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            sql += switch (s.kind()) {
                case "movies" -> " and (x.title like :search escape '!' or cast(x.id as char) like :search escape '!')";
                case "theaters" -> " and (x.name like :search escape '!' or x.address like :search escape '!')";
                default -> " and (x.movie_id in (select id from movies where title like :search escape '!') or x.screen_id in (select s.id from screens s join theaters t on t.id=s.theater_id where t.name like :search escape '!'))";
            };
        }
        if ("showtimes".equals(s.kind())) {
            if (s.movieId() != null) { sql += " and x.movie_id=:movie"; p.put("movie", s.movieId()); }
            if (s.theaterId() != null) { sql += " and x.screen_id in (select id from screens where theater_id=:theater)"; p.put("theater", s.theaterId()); }
            if (s.date() != null) {
                sql += " and x.start_time>=:start and x.start_time<:end";
                p.put("start", CinemaDay.start(s.date())); p.put("end", CinemaDay.start(s.date().plusDays(1)));
            }
        }
        return new Filter(sql, p);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Scope s, int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("잘못된 페이지입니다.");
        if ("showtimes".equals(s.kind()) && (s.movieId() == null || s.theaterId() == null || s.date() == null))
            return Map.of("items", List.of(), "total", 0L);
        var f = filter(s);
        String select = switch (s.kind()) {
            case "movies" -> "x.id,x.title,x.poster_url,x.running_time,x.rating,x.is_active,cast(x.release_date as char) as release_date,x.metadata_fetched_at";
            case "theaters" -> "x.id,x.name,x.brand,x.address,x.is_active";
            default -> "x.id,m.title,t.name as theater_name,sc.name as screen_name,x.start_time,x.end_time,x.status,x.available_seats,x.total_seats,x.price_per_person";
        };
        String from = " from " + table(s.kind()) + " x" + ("showtimes".equals(s.kind())
                ? " join movies m on m.id=x.movie_id join screens sc on sc.id=x.screen_id join theaters t on t.id=sc.theater_id" : "");
        return Map.of("items", db.queryForList("select " + select + from + " where " + f.sql() + ("showtimes".equals(s.kind()) ? " order by x.start_time,x.id" : " order by x.id desc") + " limit 50 offset " + page * 50, f.params()),
                "total", db.queryForObject("select count(*) from " + table(s.kind()) + " x where " + f.sql(), f.params(), Long.class));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> browse(Long theaterId, Long movieId) {
        return Map.of(
                "theaters", db.queryForList("select id,name,brand from theaters order by brand,name,id", Map.of()),
                "movies", db.queryForList("select id,title,case when release_date<=:releaseDate then 'now' else 'upcoming' end as movie_status from movies order by title,id",
                        Map.of("releaseDate", movieReferenceDate())),
                "dates", theaterId == null || movieId == null ? List.of() : dates(theaterId, movieId));
    }

    private java.time.LocalDate movieReferenceDate() {
        return referenceDate.isBlank() ? java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) : java.time.LocalDate.parse(referenceDate);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> dates(long theaterId, long movieId) {
        return db.queryForList("""
                select distinct cast(date(date_sub(sh.start_time, interval 4 hour)) as char) as date from showtimes sh
                join screens sc on sc.id=sh.screen_id
                where sc.theater_id=:theater and sh.movie_id=:movie order by date
                """, Map.of("theater", theaterId, "movie", movieId));
    }

    @org.springframework.beans.factory.annotation.Value("${tmdb.movie-ids:}")
    private String collectionMovieIds = "";
    @org.springframework.beans.factory.annotation.Value("${showtime.seed.screen-count:10}")
    private int collectionScreenCount = 10;
    @org.springframework.beans.factory.annotation.Value("${showtime.seed.days:3}")
    private int collectionDays = 3;

    public record CollectionCount(String key, String label, long count, long expected, long missing, String detail) {}

    /** Counts only; no entity hydration, external requests or writes. Requested once per screen refresh. */
    @Transactional(readOnly = true)
    public List<CollectionCount> collectionStatus() {
        var result = new ArrayList<CollectionCount>();
        var ids = Arrays.stream(collectionMovieIds.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .map(Long::valueOf).distinct().toList();
        long movies = ids.isEmpty() ? 0 : db.queryForObject("""
                select count(*) from movies where tmdb_movie_id in (:ids)
                and metadata_fetched_at is not null and image_metadata_fetched_at is not null and genres is not null
                """, Map.of("ids", ids), Long.class);
        result.add(new CollectionCount("movies", "영화 수집", movies, ids.size(), ids.size() - movies,
                "설정된 영화 ID 중 상세·이미지·장르 정보 수집을 확인한 영화 수입니다. 원본에 없는 포스터·영상은 누락으로 세지 않습니다."));
        var queries = SeoulTheaterCollectionService.collectionKeys();
        long complete = db.queryForObject("select count(*) from theater_collection_progress where id in (:keys) and complete=true",
                Map.of("keys", queries), Long.class);
        long theaters = count("theaters", "is_active=true", Map.of());
        result.add(new CollectionCount("theaters", "영화관 수집", complete, queries.size(), queries.size() - complete,
                "활성 영화관 " + theaters + "개 · 서울 지역×브랜드 검색 완료 수입니다. 영화관 전체 개수를 보장하는 수치는 아닙니다."));
        var keys = java.util.stream.IntStream.rangeClosed(1, collectionScreenCount).mapToObj(n -> "schedule-v1-" + n).toList();
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        Map<String, Object> p = Map.of("keys", keys, "from", CinemaDay.start(today),
                "until", CinemaDay.start(today.plusDays(collectionDays)));
        String screens = " from screens sc join theaters t on t.id=sc.theater_id where sc.is_active=true and t.is_active=true and sc.seed_key in (:keys)";
        long expectedScreens = theaters * collectionScreenCount;
        long actualScreens = db.queryForObject("select count(*)" + screens, p, Long.class);
        result.add(new CollectionCount("screens", "상영관", actualScreens, expectedScreens, Math.max(0, expectedScreens - actualScreens),
                "활성 영화관마다 자동 생성 상영관 " + collectionScreenCount + "개를 기준으로 셉니다."));
        long covered = db.queryForObject("""
                select count(distinct sh.screen_id, date(date_sub(sh.start_time, interval 4 hour)))
                from showtimes sh join screens sc on sc.id=sh.screen_id join theaters t on t.id=sc.theater_id
                where sc.is_active=true and t.is_active=true and sc.seed_key in (:keys) and sh.status='SCHEDULED'
                and sh.start_time>=:from and sh.start_time<:until
                """, p, Long.class);
        long expectedDays = expectedScreens * collectionDays;
        result.add(new CollectionCount("schedule", "날짜별 편성", covered, expectedDays, Math.max(0, expectedDays - covered),
                "오늘부터 " + collectionDays + "일간 상영관×날짜 중 회차가 1개 이상 있는 조합 수입니다. 일부 회차 누락까지 판정하지는 않습니다. 심야는 전날 편성에 포함합니다."));
        var layouts = db.queryForList("select sc.id, (select count(*) from seats s where s.screen_id=sc.id and s.is_active=true) as n" + screens, p);
        long seatCount = layouts.stream().mapToLong(row -> ((Number)row.get("n")).longValue()).sum();
        long missingSeats = Math.max(0, expectedScreens - actualScreens) * 120
                + layouts.stream().mapToLong(row -> Math.max(0, 120 - ((Number)row.get("n")).longValue())).sum();
        result.add(new CollectionCount("seats", "좌석 배치", seatCount, expectedScreens * 120, missingSeats,
                "자동 생성 상영관당 활성 좌석 120개 기준입니다. 좌석 위치나 연결 배치의 정확성은 검사하지 않습니다."));
        var seatCounts = layouts.stream().collect(java.util.stream.Collectors.toMap(
                row -> ((Number)row.get("id")).longValue(), row -> ((Number)row.get("n")).longValue()));
        var inventory = db.queryForList("""
                select sh.id, sh.screen_id,
                    (select count(*) from showtime_seats i join seats s on s.id=i.seat_id
                     where i.showtime_id=sh.id and s.screen_id=sh.screen_id and s.is_active=true) as actual
                from showtimes sh join screens sc on sc.id=sh.screen_id join theaters t on t.id=sc.theater_id
                where sc.is_active=true and t.is_active=true and sc.seed_key in (:keys) and sh.status='SCHEDULED'
                and sh.start_time>=:from and sh.start_time<:until
                """, p);
        long expectedInventory = inventory.stream().mapToLong(row -> seatCounts.getOrDefault(((Number)row.get("screen_id")).longValue(), 0L)).sum();
        long actualInventory = inventory.stream().mapToLong(row -> ((Number)row.get("actual")).longValue()).sum();
        long missingInventory = inventory.stream().mapToLong(row -> Math.max(0, seatCounts.getOrDefault(((Number)row.get("screen_id")).longValue(), 0L) - ((Number)row.get("actual")).longValue())).sum();
        result.add(new CollectionCount("inventory", "회차별 좌석", actualInventory, expectedInventory, missingInventory,
                "편성된 회차 " + inventory.size() + "개의 활성 좌석 연결 수입니다. 예매·선점·차단된 좌석도 채워진 데이터로 셉니다. 미생성 회차와 좌석 배치는 각각의 항목에서 확인하세요."));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> summary() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String t : List.of("movies", "theaters", "screens", "showtimes", "seats", "showtime_seats"))
            result.put(t, count(t, "1=1", Map.of()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> seats(long id) {
        if (count("showtimes", "id=:id", Map.of("id", id)) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상영회차를 찾을 수 없습니다.");
        return db.queryForList("""
                select s.id,s.seat_row,s.seat_number,s.adjacency_segment,i.status,i.reservation_id,i.hold_expired_at
                from showtimes sh join seats s on s.screen_id=sh.screen_id
                left join showtime_seats i on i.seat_id=s.id and i.showtime_id=sh.id
                where sh.id=:id and s.is_active=true order by s.seat_row,s.seat_number
                """, Map.of("id", id));
    }

    private List<Long> ids(String sql, Map<String, ?> p) { return db.queryForList(sql, p, Long.class); }
    private List<Long> nonempty(List<Long> ids) { return ids.isEmpty() ? List.of(-1L) : ids; }
    private long count(String table, String where, Map<String, ?> p) {
        // Empty ID sets cannot match; avoid round trips for every dependent table.
        var matcher = java.util.regex.Pattern.compile(":([A-Za-z]+)").matcher(where);
        boolean hasList = false, empty = true;
        while (matcher.find()) {
            Object value = p.get(matcher.group(1));
            if (!(value instanceof List<?> list)) { empty = false; break; }
            hasList = true;
            if (!list.equals(List.of(-1L))) empty = false;
        }
        if (hasList && empty) return 0;
        return Objects.requireNonNull(db.queryForObject("select count(*) from " + table + " where " + where, p, Long.class));
    }

    private Plan plan(Scope s, boolean lock) {
        if (s == null) throw new IllegalArgumentException("삭제 범위를 지정해주세요.");
        String t = table(s.kind());
        if (!Set.of("selected", "filtered", "all").contains(Objects.toString(s.mode(), "")))
            throw new IllegalArgumentException("삭제 범위를 지정해주세요.");
        Filter f = "filtered".equals(s.mode()) ? filter(s) : new Filter("1=1", new HashMap<>());
        if ("selected".equals(s.mode())) {
            if (s.ids() == null || s.ids().isEmpty() || s.ids().size() > 1000 || s.ids().stream().anyMatch(x -> x == null || x <= 0))
                throw new IllegalArgumentException("삭제할 항목을 선택해주세요. 한 번에 최대 1,000개입니다.");
            f = new Filter("x.id in (:selected)", Map.of("selected", s.ids()));
        }
        List<Long> roots = ids("select x.id from " + t + " x where " + f.sql() + " order by x.id" + (lock ? " for update" : ""), f.params());
        Map<String, Object> p = new HashMap<>(); p.put("roots", nonempty(roots));
        if ("theaters".equals(t)) {
            var brands = db.queryForList("select distinct brand from theaters where id in (:roots)", p, String.class);
            p.put("collectionBrands", brands.isEmpty() ? List.of("__none__") : brands);
        }
        List<Long> screens = "theaters".equals(t) ? ids("select id from screens where theater_id in (:roots) order by id" + (lock ? " for update" : ""), p) : List.of();
        p.put("screens", nonempty(screens));
        String showWhere = switch (t) { case "movies" -> "movie_id in (:roots)"; case "theaters" -> "screen_id in (:screens)"; default -> "id in (:roots)"; };
        p.put("shows", nonempty(ids("select id from showtimes where " + showWhere + " order by id" + (lock ? " for update" : ""), p)));
        p.put("reservations", nonempty(ids("select id from reservations where showtime_id in (:shows) order by id" + (lock ? " for update" : ""), p)));
        List<Long> groups = "movies".equals(t) ? ids("select id from booking_request_groups where movie_id in (:roots) order by id" + (lock ? " for update" : ""), p) : List.of();
        p.put("groups", nonempty(groups));
        p.put("preferenceGroups", "theaters".equals(t) ? nonempty(ids("select distinct group_id from booking_group_theater_preferences where theater_id in (:roots)", p)) : List.of(-1L));
        p.put("affectedGroups", nonempty(ids("""
                select id from booking_request_groups where id in (:groups) or selected_showtime_id in (:shows)
                or id in (select request_group_id from reservations where id in (:reservations))
                or id in (select request_group_id from waiting_queues where showtime_id in (:shows))
                """ + (lock ? " for update" : ""), p)));
        p.put("users", nonempty(ids("""
                select user_id from reservations where id in (:reservations)
                union select user_id from waiting_queues where showtime_id in (:shows)
                union select user_id from booking_request_groups where id in (:groups)
                """, p)));
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        // District searches overlap: replay every district of the deleted brands so
        // any removed branch is recoverable, regardless of which query first saved it.
        // Progress invalidation and catalog deletion commit or roll back together.
        if ("theaters".equals(t)) d.put("theater_collection_progress",
                "id like 'seoul-v1:%' and substring_index(id,':',-1) in (:collectionBrands)");
        d.put("notifications", "reservation_id in (:reservations) or booking_group_id in (:groups)");
        // Stored idempotency responses can contain IDs removed by this operation.
        d.put("booking_operations", "user_id in (:users)");
        d.put("booking_group_holds", "reservation_id in (:reservations) or group_id in (:groups)");
        d.put("tickets", "reservation_id in (:reservations)");
        d.put("payments", "reservation_id in (:reservations)");
        d.put("reservation_seats", "reservation_id in (:reservations)");
        d.put("showtime_seats", "showtime_id in (:shows)");
        d.put("reservations", "id in (:reservations)");
        d.put("waiting_queues", "showtime_id in (:shows)");
        d.put("queue_counters", "showtime_id in (:shows)");
        d.put("booking_group_seat_preferences", "group_id in (:groups)");
        d.put("booking_group_theater_preferences", "group_id in (:groups)" + ("theaters".equals(t) ? " or theater_id in (:roots)" : ""));
        d.put("booking_request_groups", "id in (:groups)");
        d.put("showtimes", "id in (:shows)");
        if ("theaters".equals(t)) {
            d.put("seats", "screen_id in (:screens)"); d.put("screens", "id in (:screens)");
            d.put("user_preferred_theaters", "theater_id in (:roots)"); d.put("user_nearby_theaters", "theater_id in (:roots)");
        }
        if (!"showtimes".equals(t)) d.put(t, "id in (:roots)");
        return new Plan(p, d, roots, groups, count("booking_request_groups", "selected_showtime_id in (:shows) and id not in (:groups)", p));
    }

    private Preview preview(Plan plan) {
        Map<String, Long> counts = new LinkedHashMap<>();
        plan.deletes().forEach((t, w) -> {
            // Inventory can contain millions of rows. Preview uses the small showtime
            // table's capacity instead; actual deletion never relies on this estimate.
            if ("showtime_seats".equals(t)) counts.put("estimated_showtime_seats", db.queryForObject(
                    "select coalesce(sum(total_seats),0) from showtimes where id in (:shows)", plan.params(), Long.class));
            else counts.put(t, count(t, w, plan.params()));
        });
        counts.put("detached_booking_groups", plan.detachedGroups());
        try {
            String input = plan.roots() + "|" + plan.params().get("shows") + "|" + plan.params().get("reservations") + "|" + plan.groups() + "|" + counts;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
            boolean bookings = counts.get("reservations") + counts.get("waiting_queues") + counts.get("booking_request_groups") + plan.detachedGroups() > 0;
            return new Preview(counts, hash, bookings, plan.roots().size());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional(readOnly = true)
    public Preview preview(Scope s) { return preview(plan(s, false)); }

    @Transactional(readOnly = true)
    public List<Long> deletionRoots(DeleteRequest request) {
        var plan = plan(request.scope(), false);
        var preview = preview(plan);
        if (!"삭제".equals(request.confirmation()) || !preview.fingerprint().equals(request.fingerprint()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "데이터가 변경되었습니다. 삭제 범위를 다시 확인해주세요.");
        if (preview.hasBookings() && !request.includeBookings())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "연결된 예매 데이터 삭제에 동의해주세요.");
        return plan.roots();
    }

    @Transactional(readOnly = true)
    public List<Long> deletionShowtimes(String kind, long root) {
        String where = switch (table(kind)) {
            case "movies" -> "movie_id=:root";
            case "theaters" -> "screen_id in (select id from screens where theater_id=:root)";
            default -> "id=:root";
        };
        return ids("select id from showtimes where " + where + " order by id limit 20", Map.of("root", root));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public void deleteBatch(String kind, List<Long> ids, boolean includeBookings) {
        var scope = new Scope(kind, "selected", ids, null, null, null, null);
        var current = preview(scope);
        delete(new DeleteRequest(scope, current.fingerprint(), "삭제", includeBookings));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview delete(DeleteRequest request) {
        if (!"삭제".equals(request.confirmation())) throw new IllegalArgumentException("확인 문구 '삭제'를 입력해주세요.");
        var plan = plan(request.scope(), true);
        var preview = preview(plan);
        if (!preview.fingerprint().equals(request.fingerprint()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "데이터가 변경되었습니다. 삭제 범위를 다시 확인해주세요.");
        if (preview.hasBookings() && !request.includeBookings())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "연결된 예약·결제·대기 데이터 삭제에 동의해주세요.");
        db.update("update booking_request_groups set selected_showtime_id=null where selected_showtime_id in (:shows) and id not in (:groups)", plan.params());
        for (var entry : plan.deletes().entrySet()) db.update("delete from " + entry.getKey() + " where " + entry.getValue(), plan.params());
        // Reconcile surviving multi-showtime groups without deleting their other reservations.
        db.update("""
                update booking_request_groups g set status=case
                when exists(select 1 from reservations r where r.request_group_id=g.id and r.status='CONFIRMED') then 'COMPLETED'
                when exists(select 1 from booking_group_holds h where h.group_id=g.id) then 'HOLDING'
                when exists(select 1 from waiting_queues q where q.request_group_id=g.id and q.status in ('WAITING','PAUSED','NOTIFIED','HOLDING')) then 'ACTIVE'
                else 'CANCELLED' end, updated_at=current_timestamp where g.id in (:affectedGroups)
                """, plan.params());
        // Re-number ordered collections after removing a theater from a surviving group.
        if ("theaters".equals(request.scope().kind())) {
            var preferences = db.queryForList("select group_id,theater_id from booking_group_theater_preferences where group_id in (:preferenceGroups) order by group_id,preference_order", plan.params());
            // Move indices out of the way before assigning contiguous positions (unique group/order key).
            db.update("update booking_group_theater_preferences set preference_order=-preference_order-1 where group_id in (:preferenceGroups)", plan.params());
            Map<Long, Integer> positions = new HashMap<>();
            for (var pref : preferences) {
                long group = ((Number) pref.get("group_id")).longValue();
                int position = positions.merge(group, 1, Integer::sum) - 1;
                db.update("update booking_group_theater_preferences set preference_order=:position where group_id=:group and theater_id=:theater",
                        Map.of("position", position, "group", group, "theater", pref.get("theater_id")));
            }
        }
        return preview;
    }

}
