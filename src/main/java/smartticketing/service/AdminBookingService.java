package smartticketing.service;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Administrative actions apply to whole reservations; global purge preserves catalog and account records. */
@Service
public class AdminBookingService {
    private final NamedParameterJdbcTemplate db;
    public AdminBookingService(DataSource source) { db = new NamedParameterJdbcTemplate(source); db.getJdbcTemplate().setQueryTimeout(20); }
    public record Scope(long showtimeId, String mode, List<Long> seatIds, String action) {}
    public record Request(Scope scope, String fingerprint, String confirmation) {}
    public record Preview(long reservations, long waitingQueues, List<String> seats, Map<String, Long> counts, String fingerprint) {}
    private record Plan(Map<String, Object> p, List<Long> reservations, List<Long> groups, List<Long> users, List<Long> queues) {}
    private List<Long> ids(String sql, Map<String, ?> p) { return db.queryForList(sql, p, Long.class); }
    private List<Long> nonempty(List<Long> ids) { return ids.isEmpty() ? List.of(-1L) : ids; }
    private long count(String table, String where, Map<String, ?> p) {
        return Objects.requireNonNull(db.queryForObject("select count(*) from " + table + " where " + where, p, Long.class));
    }
    private void conflict() { throw new ResponseStatusException(HttpStatus.CONFLICT, "예매 상태가 변경되었습니다. 처리 대상을 다시 확인해주세요."); }

    private Plan plan(Scope s) {
        boolean global = s != null && "global".equals(s.mode()) && "purge".equals(s.action()) && s.showtimeId() == 0;
        if (s == null || (!global && (s.showtimeId() <= 0 || !Set.of("selected", "all").contains(Objects.toString(s.mode(), ""))))
                || !Set.of("cancel", "purge").contains(Objects.toString(s.action(), "")))
            throw new IllegalArgumentException("회차와 처리 범위를 확인해주세요.");
        Map<String, Object> p = new HashMap<>(); p.put("show", s.showtimeId());
        p.put("global", global);
        if (!global && count("showtimes", "id=:show", p) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상영회차를 찾을 수 없습니다.");
        String where = "(:global=true or r.showtime_id=:show)";
        if ("selected".equals(s.mode())) {
            if (s.seatIds() == null || s.seatIds().isEmpty() || s.seatIds().size() > 1000 || s.seatIds().stream().anyMatch(id -> id == null || id <= 0))
                throw new IllegalArgumentException("좌석을 선택해주세요.");
            p.put("seats", new TreeSet<>(s.seatIds()));
            if (count("seats", "id in (:seats) and screen_id=(select screen_id from showtimes where id=:show)", p) != new HashSet<>(s.seatIds()).size())
                throw new IllegalArgumentException("다른 상영관의 좌석은 선택할 수 없습니다.");
            where += " and r.id in (select reservation_id from reservation_seats where seat_id in (:seats))";
        }
        if ("cancel".equals(s.action())) where += " and r.status in ('PENDING','CONFIRMED')";
        var reservations = ids("select r.id from reservations r where " + where + " order by r.id", p);
        p.put("reservations", nonempty(reservations));
        p.put("affectedShows", global ? nonempty(ids("select distinct showtime_id from reservations where id in (:reservations) order by showtime_id", p)) : List.of(s.showtimeId()));
        p.put("allQueues", global || "all".equals(s.mode()));
        var queues = ids("""
                select id from waiting_queues where (:global=true or showtime_id=:show) and
                ( :allQueues=true or id in (select waiting_queue_id from reservations where id in (:reservations))
                or request_group_id in (select request_group_id from reservations where id in (:reservations))) order by id
                """, p);
        // A queue referenced by a surviving reservation is preserved during record deletion.
        p.put("queues", nonempty(queues));
        if ("purge".equals(s.action())) {
            queues = ids("select id from waiting_queues where id in (:queues) and not exists(select 1 from reservations r where r.waiting_queue_id=waiting_queues.id and r.id not in (:reservations)) order by id", p);
            p.put("queues", nonempty(queues));
        } else {
            queues = ids("select id from waiting_queues where id in (:queues) and status in ('WAITING','PAUSED','NOTIFIED','HOLDING') order by id", p);
            p.put("queues", nonempty(queues));
        }
        var groups = ids("""
                select id from booking_request_groups where :global=true or id in (select request_group_id from reservations where id in (:reservations))
                or id in (select request_group_id from waiting_queues where id in (:queues)) order by id
                """, p);
        p.put("groups", nonempty(groups));
        var users = ids("select user_id from reservations where id in (:reservations) union select user_id from waiting_queues where id in (:queues) union select user_id from booking_request_groups where id in (:groups) union select user_id from booking_operations where :global=true", p).stream().sorted().toList();
        p.put("users", nonempty(users));
        p.put("orphanGroups", "purge".equals(s.action()) ? nonempty(ids("""
                select g.id from booking_request_groups g where g.id in (:groups)
                and not exists(select 1 from reservations r where r.request_group_id=g.id and r.id not in (:reservations))
                and not exists(select 1 from waiting_queues q where q.request_group_id=g.id and q.id not in (:queues))
                and not exists(select 1 from booking_group_holds h where h.group_id=g.id and h.reservation_id not in (:reservations)) order by g.id
                """, p)) : List.of(-1L));
        return new Plan(p, reservations, groups, users, queues);
    }

    private Preview snapshot(Scope s, Plan plan) {
        var p = plan.p(); var counts = new LinkedHashMap<String, Long>();
        counts.put("reservations", (long) plan.reservations().size());
        counts.put("reservation_seats", count("reservation_seats", "reservation_id in (:reservations)", p));
        counts.put("payments", count("payments", "reservation_id in (:reservations)", p));
        counts.put("tickets", count("tickets", "reservation_id in (:reservations)", p));
        counts.put("waiting_queues", (long) plan.queues().size());
        counts.put("queue_counters", count("queue_counters", ":global=true", p));
        counts.put("booking_group_holds", count("booking_group_holds", "reservation_id in (:reservations)", p));
        if ("purge".equals(s.action())) {
            counts.put("booking_operations", count("booking_operations", "user_id in (:users)", p));
            counts.put("notifications", count("notifications", "reservation_id in (:reservations) or booking_group_id in (:orphanGroups)", p));
            counts.put("booking_request_groups", count("booking_request_groups", "id in (:orphanGroups)", p));
        }
        var seats = db.queryForList("select distinct concat(s.seat_row,s.seat_number) as label from reservation_seats rs join seats s on s.id=rs.seat_id where rs.reservation_id in (:reservations) order by label", p, String.class);
        try {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((s.toString() + counts + p.get("orphanGroups")).getBytes(StandardCharsets.UTF_8));
        for (String query : List.of(
                "select * from reservations where id in (:reservations) order by id",
                "select * from reservation_seats where reservation_id in (:reservations) order by reservation_id,seat_id",
                "select * from showtime_seats where (:global=false and showtime_id=:show) or reservation_id in (:reservations) order by id",
                "select * from waiting_queues where id in (:queues) order by id",
                "select * from booking_request_groups where id in (:groups) order by id",
                "select * from payments where reservation_id in (:reservations) order by id",
                "select * from tickets where reservation_id in (:reservations) order by id")) {
            digest.update(query.getBytes(StandardCharsets.UTF_8));
            db.query(query, p, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                    String value = row.getString(column);
                    byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
                    digest.update(bytes);
                }
            });
        }
            var hash = HexFormat.of().formatHex(digest.digest());
            return new Preview(plan.reservations().size(), plan.queues().size(), seats, counts, hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(Scope s) { return snapshot(s, plan(s)); }

    @Transactional(readOnly = true)
    public List<Long> globalShowtimes(Request request) {
        var plan = plan(request.scope());
        if (!"global".equals(request.scope().mode()) || !"삭제".equals(request.confirmation())
                || !snapshot(request.scope(), plan).fingerprint().equals(request.fingerprint())) conflict();
        if (count("payments", "reservation_id in (:reservations) and payment_method<>'MOCK'", plan.p()) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "외부 결제는 결제사 환불 처리가 필요합니다.");
        return ids("select showtime_id from reservations union select showtime_id from waiting_queues order by showtime_id", Map.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public Preview purgeShow(long showtimeId) {
        var scope = new Scope(showtimeId, "all", null, "purge");
        return execute(new Request(scope, preview(scope).fingerprint(), "삭제"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public Preview purgeRemainingGroups() {
        var scope = new Scope(0, "global", null, "purge");
        return execute(new Request(scope, preview(scope).fingerprint(), "삭제"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Preview execute(Request request) {
        if (request == null || request.scope() == null) throw new IllegalArgumentException("처리 대상을 확인해주세요.");
        var s = request.scope(); boolean purge = "purge".equals(s.action());
        if (!(purge ? "삭제" : "취소").equals(request.confirmation())) throw new IllegalArgumentException("확인 문구를 입력해주세요.");
        var before = plan(s); var p = before.p();
        // Match booking writers: idempotency records -> groups/holds -> show -> reservations/inventory.
        ids("select id from booking_operations where user_id in (:users) order by id for update", p);
        ids("select id from booking_request_groups where id in (:groups) order by id for update", p);
        ids("select group_id from booking_group_holds where group_id in (:groups) order by group_id for update", p);
        ids("select id from showtimes where id in (:affectedShows) order by id for update", p);
        var locked = plan(s);
        if (!before.groups().equals(locked.groups()) || !before.users().equals(locked.users())) conflict();
        p = locked.p();
        ids("select id from reservations where id in (:reservations) order by id for update", p);
        ids("select id from showtime_seats where (:global=false and showtime_id=:show) or reservation_id in (:reservations) order by id for update", p);
        ids("select id from waiting_queues where id in (:queues) order by id for update", p);
        ids("select id from payments where reservation_id in (:reservations) order by id for update", p);
        ids("select id from tickets where reservation_id in (:reservations) order by id for update", p);
        var preview = snapshot(s, locked);
        if (!preview.fingerprint().equals(request.fingerprint())) conflict();
        // No external payment gateway is called: this application stores MOCK payments only.
        if (count("payments", "reservation_id in (:reservations) and payment_method<>'MOCK'", p) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "외부 결제는 결제사 환불 처리가 필요합니다.");
        db.update("update showtime_seats set status='AVAILABLE',reservation_id=null,hold_expired_at=null where (:global=true or showtime_id=:show) and reservation_id in (:reservations)", p);
        db.update("delete from booking_group_holds where reservation_id in (:reservations)", p);
        if (purge) {
            db.update("delete from notifications where reservation_id in (:reservations) or booking_group_id in (:orphanGroups)", p);
            db.update("delete from booking_operations where user_id in (:users)", p);
            for (String t : List.of("tickets", "payments", "reservation_seats")) db.update("delete from " + t + " where reservation_id in (:reservations)", p);
            db.update("delete from reservations where id in (:reservations)", p);
            db.update("delete from waiting_queues where id in (:queues)", p);
            db.update("delete from queue_counters where :global=true", p);
            for (String t : List.of("booking_group_seat_preferences", "booking_group_theater_preferences")) db.update("delete from " + t + " where group_id in (:orphanGroups)", p);
            db.update("delete from booking_request_groups where id in (:orphanGroups)", p);
        } else {
            for (String t : List.of("payments", "tickets")) db.update("update " + t + " set status='CANCELLED',updated_at=current_timestamp where reservation_id in (:reservations)", p);
            db.update("update reservations set status='CANCELLED',expires_at=null,updated_at=current_timestamp where id in (:reservations)", p);
            db.update("update waiting_queues set status='CANCELLED',opportunity_expires_at=null,updated_at=current_timestamp where id in (:queues)", p);
        }
        db.update("""
                update booking_request_groups g set status=case
                when exists(select 1 from reservations r where r.request_group_id=g.id and r.status='CONFIRMED') then 'COMPLETED'
                when exists(select 1 from booking_group_holds h where h.group_id=g.id) then 'HOLDING'
                when exists(select 1 from waiting_queues q where q.request_group_id=g.id and q.status in ('WAITING','PAUSED','NOTIFIED','HOLDING')) then 'ACTIVE'
                else 'CANCELLED' end, updated_at=current_timestamp where g.id in (:groups)
                """, p);
        if (purge) db.update("""
                update booking_request_groups g set selected_showtime_id=null where g.id in (:groups) and selected_showtime_id=:show
                and not exists(select 1 from reservations r where r.request_group_id=g.id and r.showtime_id=:show)
                """, p);
        db.update("""
                update showtimes set available_seats=(select count(*) from showtime_seats i join seats s on s.id=i.seat_id
                where i.showtime_id=showtimes.id and s.is_active=true and (i.status='AVAILABLE' or (i.status='HOLDING' and i.hold_expired_at<=current_timestamp))),updated_at=current_timestamp where id in (:affectedShows)
                """, p);
        return preview;
    }
}
