package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.dto.booking.BookingResult;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;

import java.time.LocalDateTime;
import java.util.*;

/** Search outside domain locks; each contention retry gets a fresh transaction and snapshot. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BookingSmartService {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingIdempotency operations;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private record Intent(Long groupId) {}
    private record Ranked(Long showId, int theaterRank, LocalDateTime start, Long theaterId, SmartSeatCandidates.Block block) {}
    private record Search(BookingHoldService.Candidate candidate, String code, String message) {
        static Search failure(String code, String message) { return new Search(null, code, message); }
    }
    private static final class Contention extends RuntimeException {}
    static final int MAX_ATTEMPTS = 3;

    public BookingSmartService(EntityManager em, BookingHoldService holds, BookingIdempotency operations,
                               PlatformTransactionManager transactions) {
        this.em = em; this.holds = holds; this.operations = operations;
        read = new TransactionTemplate(transactions); read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BookingResult hold(Long userId, Long groupId, String key) {
        BookingIdempotency.key(key);
        if (groupId == null || groupId < 1) throw new IllegalArgumentException("유효한 그룹 ID가 필요합니다.");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var search = read.execute(status -> search(userId, groupId));
            try { return attempt(userId, groupId, key, search); }
            catch (Contention retry) { /* Entire transaction rolled back, including request record. */ }
        }
        return attempt(userId, groupId, key, Search.failure("RETRY_EXHAUSTED",
                "다른 관객이 좌석을 먼저 확보했습니다. 다시 탐색하거나 조건을 변경해주세요."));
    }

    private BookingResult attempt(Long userId, Long groupId, String key, Search search) {
        return write.execute(status -> {
            // Also discard an OSIV persistence context's old search entities before locking reads.
            em.clear();
            holds.lockCapacityUser(userId);
            holds.requireUser(userId);
            return operations.execute(userId, BookingOperationType.SMART_HOLD, key, new Intent(groupId), holds.now(), () -> {
                var group = holds.lockOwnedGroup(userId, groupId);
                if (group.getEntryPoint() == BookingEntryPoint.THEATER_NORMAL)
                    throw new BookingRejection(400, "SMART_GROUP_REQUIRED", "스마트예매 그룹이 필요합니다.");
                if (group.getStatus() != BookingGroupStatus.ACTIVE)
                    throw new BookingRejection(409, "GROUP_UNAVAILABLE", "이미 선점했거나 종료된 관람 요청입니다. 현재 예약을 확인해주세요.");
                if (search.candidate() == null) throw new BookingRejection(409, search.code(), search.message());
                try {
                    var candidate = search.candidate();
                    return holds.acquire(userId, groupId, BookingHoldService.Source.SMART, candidate.showtimeId(), candidate.seatIds());
                } catch (BookingRejection failure) {
                    if ("SEAT_CONFLICT".equals(failure.code)) throw new Contention();
                    throw failure;
                }
            });
        });
    }

    private Search search(Long userId, Long groupId) {
        em.clear();
        holds.requireUser(userId);
        var group = em.find(BookingRequestGroup.class, groupId);
        // Ownership and current status are enforced again inside the idempotent write transaction.
        if (group == null || !group.getUser().getId().equals(userId)
                || group.getEntryPoint() == BookingEntryPoint.THEATER_NORMAL || group.getStatus() != BookingGroupStatus.ACTIVE)
            return Search.failure("GROUP_UNAVAILABLE", "관람 요청을 확인해주세요.");
        var theaters = group.getTheaterPreferences().stream().map(Theater::getId).toList();
        var preferences = List.copyOf(group.getSeatPreferences());
        if (group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART && theaters.isEmpty())
            return Search.failure("NO_THEATER_SCOPE", "저장된 선호극장이 없습니다. 선호극장을 설정한 뒤 새 요청으로 진행해주세요.");
        var from = CinemaDay.start(group.getViewingDate()); var until = from.plusDays(1);
        boolean movie = group.getEntryPoint() == BookingEntryPoint.MOVIE_SMART;
        if (movie) {
            from = CinemaDay.time(group.getViewingDate(), group.getStartTimeFrom());
            until = CinemaDay.time(group.getViewingDate(), group.getStartTimeTo());
            if (!until.isAfter(from)) until = until.plusDays(1);
        }
        var query = em.createQuery("""
                select s from Showtime s join fetch s.screen sc join fetch sc.theater t
                where s.movie.id=:movie and s.movie.active=true and sc.active=true and t.active=true
                and s.status=:status and s.startTime>:now and s.startTime>=:from
                """ + (movie ? " and s.startTime<=:until and t.id in :theaters" : " and s.startTime<:until and s.id=:show"), Showtime.class)
                .setParameter("movie", group.getMovie().getId()).setParameter("status", ShowtimeStatus.SCHEDULED)
                .setParameter("now", holds.now()).setParameter("from", from).setParameter("until", until);
        if (movie) query.setParameter("theaters", theaters);
        else query.setParameter("show", group.getSelectedShowtime().getId());
        var shows = query.getResultList();
        var ended = em.createQuery("select q.showtime.id from WaitingQueue q where q.requestGroup.id=:g and q.status in :states", Long.class)
                .setParameter("g", groupId).setParameter("states", List.of(QueueStatus.EXPIRED, QueueStatus.CANCELLED)).getResultList();
        shows = shows.stream().filter(s -> !ended.contains(s.getId())).toList();
        if (shows.isEmpty()) return Search.failure("NO_SHOWTIMES", "선택 조건에 맞는 예매 가능한 회차가 없습니다.");
        var inventory = em.createQuery("""
                select i from ShowtimeSeat i join fetch i.seat where i.showtime.id in :ids order by i.id
                """, ShowtimeSeat.class).setParameter("ids", shows.stream().map(Showtime::getId).toList()).getResultList();
        var byShow = new HashMap<Long, List<ShowtimeSeat>>();
        for (var row : inventory) byShow.computeIfAbsent(row.getShowtime().getId(), ignored -> new ArrayList<>()).add(row);
        var candidates = new ArrayList<Ranked>();
        boolean knownLayout = false, unknownLayout = false, priced = false;
        int available = 0;
        for (var show : shows) {
            if (!Integer.valueOf(10000).equals(show.getPricePerPerson())) continue;
            priced = true;
            var seats = SmartSeatCandidates.analyze(byShow.getOrDefault(show.getId(), List.of()),
                    show.getScreen().getId(), group.getPartySize(), preferences, group.getCandidateZone());
            unknownLayout |= !seats.layoutComplete(); knownLayout |= seats.layoutComplete();
            available = Math.max(available, seats.available());
            for (var block : seats.blocks()) candidates.add(new Ranked(show.getId(),
                    Math.max(0, theaters.indexOf(show.getScreen().getTheater().getId())), show.getStartTime(),
                    show.getScreen().getTheater().getId(), block));
        }
        var best = candidates.stream().min(Comparator.comparing(Ranked::block, SmartSeatCandidates.priorityOrder())
                .thenComparingInt(Ranked::theaterRank).thenComparing(Ranked::start)
                .thenComparing(Ranked::theaterId).thenComparing(Ranked::showId)
                .thenComparingDouble(c -> c.block.centerDistance())
                .thenComparing(c -> c.block.row()).thenComparing(c -> c.block.segment())
                .thenComparingInt(c -> c.block.firstPosition()));
        if (best.isPresent()) return new Search(new BookingHoldService.Candidate(best.get().showId, best.get().block.seatIds()), null, null);
        if (!priced) return Search.failure("PRICE_UNVERIFIED", "회차 가격을 확인할 수 없어 자동 선점할 수 없습니다.");
        if (unknownLayout) return Search.failure("LAYOUT_UNVERIFIED", "일부 회차의 좌석 배치를 확인할 수 없고, 확인된 회차에도 허용된 좌석 조합이 없습니다.");
        if (knownLayout && available == 0) return Search.failure("SOLD_OUT", "조건에 맞는 회차의 좌석이 모두 매진되었습니다.");
        return Search.failure("NO_CONTIGUOUS_SEATS", "잔여석은 있지만 전체 연석 또는 인원별 허용된 분할 연석 조합이 없습니다.");
    }
}
