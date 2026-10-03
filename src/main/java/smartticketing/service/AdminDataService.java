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
    public AdminDataService(DataSource source) { db = new NamedParameterJdbcTemplate(source); }

    public record Scope(String kind, String mode, List<Long> ids, String search, Long movieId,
                        Long theaterId, java.time.LocalDate date) {}
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
                p.put("start", s.date().atStartOfDay()); p.put("end", s.date().plusDays(1).atStartOfDay());
            }
        }
        return new Filter(sql, p);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Scope s, int page) {
        if (page < 0 || page > 100000) throw new IllegalArgumentException("잘못된 페이지입니다.");
        var f = filter(s);
        String select = switch (s.kind()) {
            case "movies" -> "x.id,x.title,x.running_time,x.rating,x.is_active,x.metadata_fetched_at";
            case "theaters" -> "x.id,x.name,x.brand,x.address,x.is_active";
            default -> "x.id,m.title,t.name as theater_name,sc.name as screen_name,x.start_time,x.end_time,x.status,x.available_seats,x.total_seats,x.price_per_person";
        };
        String from = " from " + table(s.kind()) + " x" + ("showtimes".equals(s.kind())
                ? " join movies m on m.id=x.movie_id join screens sc on sc.id=x.screen_id join theaters t on t.id=sc.theater_id" : "");
        return Map.of("items", db.queryForList("select " + select + from + " where " + f.sql() + " order by x.id desc limit 50 offset " + page * 50, f.params()),
                "total", db.queryForObject("select count(*) from " + table(s.kind()) + " x where " + f.sql(), f.params(), Long.class));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> browse(Long theaterId, Long movieId) {
        Map<String, Object> p = new HashMap<>();
        p.put("theater", theaterId); p.put("movie", movieId);
        return Map.of(
                "theaters", db.queryForList("select id,name,brand from theaters order by brand,name,id", Map.of()),
                "movies", db.queryForList("""
                        select distinct m.id,m.title from movies m
                        join showtimes sh on sh.movie_id=m.id join screens sc on sc.id=sh.screen_id
                        where (:theater is null or sc.theater_id=:theater) order by m.title,m.id
                        """, p),
                "dates", db.queryForList("""
                        select distinct cast(date(sh.start_time) as char) as date from showtimes sh
                        join screens sc on sc.id=sh.screen_id
                        where (:theater is null or sc.theater_id=:theater)
                        and (:movie is null or sh.movie_id=:movie) order by date
                        """, p));
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
        plan.deletes().forEach((t, w) -> counts.put(t, count(t, w, plan.params())));
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

    public record Edit(String title, String name, String address, Integer runningTime, Boolean active, Integer price) {}

    @Transactional
    public void edit(String kind, long id, Edit edit) {
        String t = table(kind);
        if (ids("select id from " + t + " where id=:id for update", Map.of("id", id)).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "항목을 찾을 수 없습니다.");
        Map<String, Object> p = new HashMap<>(); p.put("id", id);
        if ("movies".equals(t)) {
            if (edit.title() == null || edit.title().isBlank() || edit.title().length() > 255 || edit.runningTime() == null || edit.runningTime() < 1 || edit.runningTime() > 1440 || edit.active() == null)
                throw new IllegalArgumentException("영화명, 상영시간(1~1440분), 활성 여부를 확인해주세요.");
            p.put("title", edit.title().trim()); p.put("runtime", edit.runningTime()); p.put("active", edit.active());
            db.update("update movies set title=:title,running_time=:runtime,is_active=:active where id=:id", p);
        } else if ("theaters".equals(t)) {
            if (edit.name() == null || edit.name().isBlank() || edit.name().length() > 100 || edit.address() == null || edit.address().length() > 255 || edit.active() == null)
                throw new IllegalArgumentException("영화관명, 주소, 활성 여부를 확인해주세요.");
            p.put("name", edit.name().trim()); p.put("address", edit.address()); p.put("active", edit.active());
            db.update("update theaters set name=:name,address=:address,is_active=:active where id=:id", p);
        } else {
            if (edit.price() == null || edit.price() < 0 || edit.price() > 1000000) throw new IllegalArgumentException("가격은 0~1,000,000원으로 입력해주세요.");
            if (count("reservations", "showtime_id=:id", p) > 0 || count("waiting_queues", "showtime_id=:id", p) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "예약·대기가 있는 회차는 가격을 변경할 수 없습니다.");
            p.put("price", edit.price()); db.update("update showtimes set price_per_person=:price,updated_at=current_timestamp where id=:id", p);
        }
    }
}
