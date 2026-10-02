package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 자동 시간표용 상영관의 빈 배치와 누락된 미래 회차 재고만 보충한다. */
@Service
public class ShowtimeInventoryService {
    private final EntityManager em;
    private final Clock clock;

    public ShowtimeInventoryService(EntityManager em, @Qualifier("bookingQueryClock") Clock clock) {
        this.em = em;
        this.clock = clock.withZone(ZoneId.of("Asia/Seoul"));
    }

    @Transactional(readOnly = true)
    public List<Long> screenIds() {
        return em.createQuery("""
                select s.id from Screen s where s.active = true and s.theater.active = true
                and s.seedKey like 'schedule-v1-%' order by s.id
                """, Long.class).getResultList();
    }

    // 상영관별 트랜잭션으로 초기 수백만 좌석 연결을 작은 단위로 처리한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result prepare(Long screenId) {
        var screen = em.find(Screen.class, screenId, LockModeType.PESSIMISTIC_WRITE);
        if (screen == null || !screen.isActive() || !screen.getTheater().isActive()
                || screen.getSeedKey() == null || !screen.getSeedKey().startsWith("schedule-v1-"))
            return new Result(0, 0, 0);

        long seatCount = em.createQuery("select count(s) from Seat s where s.screen.id = :id", Long.class)
                .setParameter("id", screenId).getSingleResult();
        int createdSeats = 0;
        // 일부 좌석만 있거나 비활성 좌석이 있어도 기존 배치를 임의로 확장/복원하지 않는다.
        if (seatCount == 0) {
            // IDENTITY 엔티티를 한 석씩 persist하면 120번 왕복한다. 고정 배치는 한 SQL로 넣는다.
            var layout = DefaultSeatLayout.create(screen);
            var tuples = new java.util.ArrayList<String>(layout.size());
            for (int i = 0; i < layout.size(); i++)
                tuples.add("(:screen, :row" + i + ", :number" + i + ", :position" + i
                        + ", :segment" + i + ", :offset" + i + ", true)");
            var insert = em.createNativeQuery("""
                    INSERT INTO seats (screen_id, seat_row, seat_number, seat_position,
                        adjacency_segment, position_in_segment, is_active) VALUES
                    """ + String.join(",", tuples)).setParameter("screen", screenId);
            for (int i = 0; i < layout.size(); i++) {
                var seat = layout.get(i);
                insert.setParameter("row" + i, seat.getSeatRow()).setParameter("number" + i, seat.getSeatNumber())
                        .setParameter("position" + i, seat.getSeatPosition().name())
                        .setParameter("segment" + i, seat.getAdjacencySegment())
                        .setParameter("offset" + i, seat.getPositionInSegment());
            }
            createdSeats = insert.executeUpdate();
        }

        var now = LocalDateTime.now(clock);
        int updatedSeats = seatCount == 120 ? migrateLegacyLayout(screenId, now) : 0;
        var seatIds = em.createQuery("select s.id from Seat s where s.screen.id = :id and s.active = true", Long.class)
                .setParameter("id", screenId).getResultList();
        if (seatIds.isEmpty()) return new Result(createdSeats, 0, 0, updatedSeats);
        // (showtime_id, seat_id) 유니크 인덱스에서 개수만 확인한다. 완성된 회차에는 DML/잠금이 없다.
        var incompleteIds = em.createQuery("""
                select s.id from Showtime s where s.screen.id = :screen and s.status = :status
                and s.startTime > :now and
                (select count(i) from ShowtimeSeat i where i.showtime = s and i.seat.id in :seats) < :expected
                order by s.id
                """, Long.class).setParameter("screen", screenId).setParameter("status", ShowtimeStatus.SCHEDULED)
                .setParameter("now", now).setParameter("seats", seatIds).setParameter("expected", (long) seatIds.size())
                .getResultList();
        if (incompleteIds.isEmpty()) return new Result(createdSeats, 0, 0, updatedSeats);
        // 실제 보충이 필요한 회차만 예매 처리와 같은 잠금으로 직렬화한다.
        var shows = em.createQuery("""
                select s from Showtime s where s.id in :ids and s.status = :status
                and s.startTime > :now order by s.id
                """, Showtime.class).setParameter("ids", incompleteIds)
                .setParameter("status", ShowtimeStatus.SCHEDULED).setParameter("now", now)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (shows.isEmpty()) return new Result(createdSeats, 0, 0, updatedSeats);
        var showIds = shows.stream().map(Showtime::getId).toList();
        em.flush();

        // 엔티티를 수백만 개 적재하지 않고 없는 (회차, 좌석) 조합만 일괄 추가한다.
        int createdInventory = em.createNativeQuery("""
                INSERT INTO showtime_seats (showtime_id, seat_id, status)
                SELECT sh.id, seat.id, 'AVAILABLE'
                FROM showtimes sh JOIN seats seat ON seat.screen_id = sh.screen_id AND seat.is_active = true
                LEFT JOIN showtime_seats existing ON existing.showtime_id = sh.id AND existing.seat_id = seat.id
                WHERE sh.id IN (:ids) AND existing.id IS NULL
                """).setParameter("ids", showIds).executeUpdate();
        int updatedShows = 0;
        if (createdInventory > 0) {
            var counts = em.createQuery("""
                    select i.showtime.id, count(i), sum(case when i.status = :available then 1 else 0 end)
                    from ShowtimeSeat i where i.showtime.id in :ids
                    and i.seat.active = true and i.seat.screen.id = :screen group by i.showtime.id
                    """, Object[].class).setParameter("available", SeatStatus.AVAILABLE)
                    .setParameter("screen", screenId).setParameter("ids", showIds).getResultList();
            var byId = new java.util.HashMap<Long, Showtime>();
            shows.forEach(show -> byId.put(show.getId(), show));
            for (var count : counts) {
                var show = byId.get((Long) count[0]);
                int total = ((Number) count[1]).intValue();
                int available = ((Number) count[2]).intValue();
                if (!java.util.Objects.equals(show.getTotalSeats(), total)
                        || !java.util.Objects.equals(show.getAvailableSeats(), available)) {
                    show.setTotalSeats(total);
                    show.setAvailableSeats(available);
                    updatedShows++;
                }
            }
        }
        return new Result(createdSeats, createdInventory, updatedShows, updatedSeats);
    }

    private int migrateLegacyLayout(Long screenId, LocalDateTime now) {
        // A3 경계만 먼저 확인해 이미 3·6·3인 배치는 전체 배치 검사를 생략한다.
        long legacyBoundary = em.createQuery("""
                select count(s) from Seat s where s.screen.id = :screen and s.seatRow = 'A'
                and s.seatNumber = 3 and s.adjacencySegment = 'center' and s.positionInSegment = 1
                """, Long.class).setParameter("screen", screenId).getSingleResult();
        if (legacyBoundary == 0) return 0;
        // 이전 자동 생성 배치와 120석 모두 정확히 일치할 때만 변환한다. 사용자 편집 배치는 보존한다.
        var matches = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM seats WHERE screen_id = :screen AND is_active = true
                AND seat_row REGEXP '^[A-J]$' AND seat_number BETWEEN 1 AND 12
                AND adjacency_segment = CASE WHEN seat_number <= 2 THEN 'left' WHEN seat_number <= 10 THEN 'center' ELSE 'right' END
                AND position_in_segment = CASE WHEN seat_number <= 2 THEN seat_number WHEN seat_number <= 10 THEN seat_number-2 ELSE seat_number-10 END
                AND seat_position = CONCAT(CASE WHEN seat_number BETWEEN 3 AND 10 THEN 'MIDDLE_' ELSE 'SIDE_' END,
                    CASE WHEN seat_row <= 'C' THEN 'FRONT' WHEN seat_row <= 'G' THEN 'MIDDLE' ELSE 'REAR' END)
                """).setParameter("screen", screenId).getSingleResult();
        if (matches.intValue() != 120) return 0;
        em.createQuery("""
                select s from Showtime s where s.screen.id = :screen and s.status = :status
                and s.startTime > :now order by s.id
                """, Showtime.class).setParameter("screen", screenId).setParameter("status", ShowtimeStatus.SCHEDULED)
                .setParameter("now", now).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        // 좌석 ID와 showtime_seats의 상태/예약/만료 시각은 변경하지 않는다.
        return em.createNativeQuery("""
                UPDATE seats SET
                    adjacency_segment = CASE WHEN seat_number <= 3 THEN 'left' WHEN seat_number <= 9 THEN 'center' ELSE 'right' END,
                    position_in_segment = CASE WHEN seat_number <= 3 THEN seat_number WHEN seat_number <= 9 THEN seat_number-3 ELSE seat_number-9 END,
                    seat_position = CONCAT(CASE WHEN seat_number BETWEEN 4 AND 9 THEN 'MIDDLE_' ELSE 'SIDE_' END,
                        CASE WHEN seat_row <= 'C' THEN 'FRONT' WHEN seat_row <= 'G' THEN 'MIDDLE' ELSE 'REAR' END)
                WHERE screen_id = :screen AND seat_number >= 3
                """).setParameter("screen", screenId).executeUpdate();
    }

    public record Result(int createdSeats, int createdShowtimeSeats, int updatedShowtimes, int updatedSeats) {
        public Result(int createdSeats, int createdShowtimeSeats, int updatedShowtimes) {
            this(createdSeats, createdShowtimeSeats, updatedShowtimes, 0);
        }
    }
}
