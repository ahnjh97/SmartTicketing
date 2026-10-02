package smartticketing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import smartticketing.dto.booking.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.util.*;
import static smartticketing.service.BookingHoldService.reject;

/** Mock only. All inventory, payment and ticket writes share the hold transaction/lock order. */
@Service
@Transactional
public class BookingPaymentService {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingIdempotency operations;
    private final TicketService tickets;
    private final NotificationService notifications;
    private final boolean allowFailure;

    public BookingPaymentService(EntityManager em, BookingHoldService holds, BookingIdempotency operations,
            TicketService tickets, NotificationService notifications, Environment environment,
            @Value("${booking.mock-payment.allow-failure:false}") boolean allowFailure) {
        this.em = em; this.holds = holds; this.operations = operations; this.tickets = tickets;
        this.notifications = notifications;
        this.allowFailure = allowFailure && environment.acceptsProfiles(Profiles.of("dev", "test"));
    }

    private record Intent(Long reservationId, MockPaymentRequest request) {}
    private record Locked(BookingRequestGroup group, BookingGroupHold slot, Showtime show, Reservation reservation) {}

    public BookingResult pay(Long userId, Long reservationId, String key, MockPaymentRequest request) {
        BookingIdempotency.key(key); holds.requireUser(userId);
        if (request == null || request.paymentMethod() != PaymentMethod.MOCK)
            throw new IllegalArgumentException("모의결제 방식이 필요합니다.");
        var normalized = new MockPaymentRequest(request.paymentMethod(), Boolean.TRUE.equals(request.simulateFailure()));
        return operations.execute(userId, BookingOperationType.CONFIRM_PAYMENT, key,
                new Intent(reservationId, normalized), holds.now(), () -> payLocked(userId, reservationId, normalized));
    }

    private PaymentResponse payLocked(Long userId, Long id, MockPaymentRequest request) {
        var locked = lock(userId, id);
        var r = locked.reservation();
        if (request.simulateFailure() && !allowFailure) reject(400, "결제 실패 시뮬레이션은 명시적으로 활성화한 개발/테스트 환경에서만 가능합니다.");
        if (r.getStatus() == ReservationStatus.CONFIRMED) return paymentResponse(r, payment(id), true);
        if (r.getStatus() != ReservationStatus.PENDING) reject(409, "결제할 수 없는 예약 상태입니다.");
        if (r.getExpiresAt() == null || !r.getExpiresAt().isAfter(holds.now())) {
            holds.expireLockedGroup(locked.group());
            reject(409, "좌석 선점 시간이 만료되었습니다.");
        }
        BookingHoldService.validateShow(locked.show(), holds.now());
        var inventory = holds.lockInventory(locked.show().getId());
        var owned = validateInventory(locked, inventory, SeatStatus.HOLDING);
        // Time is rechecked after all potentially blocking inventory locks.
        if (!r.getExpiresAt().isAfter(holds.now())) {
            holds.expireLockedGroup(locked.group());
            reject(409, "좌석 선점 시간이 만료되었습니다.");
        }
        if (!locked.show().getStartTime().isAfter(holds.now())) reject(409, "이미 시작한 회차입니다.");
        var prices = em.createQuery("select s.price from ReservationSeat s where s.reservation.id=:id", Integer.class)
                .setParameter("id", id).getResultList();
        if (prices.isEmpty() || prices.stream().anyMatch(p -> p == null || p <= 0)
                || r.getTotalAmount() == null || prices.stream().mapToLong(Integer::longValue).sum() != r.getTotalAmount())
            throw new IllegalStateException("저장된 예매 금액이 일치하지 않습니다.");
        var payment = payment(id);
        if (payment != null && payment.getStatus() != PaymentStatus.FAILED && payment.getStatus() != PaymentStatus.READY)
            throw new IllegalStateException("결제와 예약 상태가 일치하지 않습니다.");
        var now = holds.now();
        if (payment == null) {
            payment = new Payment(); payment.setReservation(r); payment.setCreatedAt(now); payment.setUpdatedAt(now);
            payment.setPaymentMethod(PaymentMethod.MOCK); payment.setAmount(r.getTotalAmount()); em.persist(payment);
        }
        if (!Objects.equals(payment.getAmount(), r.getTotalAmount())) throw new IllegalStateException("결제 금액 불일치");
        payment.setUpdatedAt(now);
        if (request.simulateFailure()) {
            payment.setStatus(PaymentStatus.FAILED);
            NotificationService.link(notifications.paymentFailed(userId), r);
            return paymentResponse(r, payment, false);
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        owned.forEach(seat -> { seat.setStatus(SeatStatus.RESERVED); seat.setHoldExpiredAt(null); });
        r.setStatus(ReservationStatus.CONFIRMED); r.setUpdatedAt(now);
        locked.group().setStatus(BookingGroupStatus.COMPLETED); locked.group().setUpdatedAt(now);
        BookingQueueLifecycle.completed(em, locked.group().getId(), now);
        em.remove(locked.slot());
        BookingHoldService.updateAvailable(locked.show(), inventory, now);
        return paymentResponse(r, payment, true);
    }

    private Locked lock(Long userId, Long id) {
        // Read IDs only: never hydrate a stale snapshot before the locking reads.
        var refs = em.createQuery("select r.requestGroup.id, r.showtime.id from Reservation r where r.id=:id and r.user.id=:user", Object[].class)
                .setParameter("id", id).setParameter("user", userId).getResultList();
        if (refs.isEmpty() || refs.getFirst()[0] == null) { reject(404, "예약을 찾을 수 없습니다."); }
        var ref = refs.getFirst();
        var group = holds.lockOwnedGroup(userId, (Long) ref[0]);
        var slot = group.getStatus() == BookingGroupStatus.HOLDING
                ? em.find(BookingGroupHold.class, group.getId(), LockModeType.PESSIMISTIC_WRITE) : null;
        BookingQueueLifecycle.lockShows(em, group.getId(), (Long) ref[1]);
        var show = em.find(Showtime.class, (Long) ref[1], LockModeType.PESSIMISTIC_WRITE);
        var reservation = em.find(Reservation.class, id, LockModeType.PESSIMISTIC_WRITE);
        return new Locked(group, slot, show, reservation);
    }

    private List<ShowtimeSeat> validateInventory(Locked locked, List<ShowtimeSeat> inventory, SeatStatus status) {
        var r = locked.reservation();
        if (status == SeatStatus.HOLDING && (locked.group().getStatus() != BookingGroupStatus.HOLDING
                || locked.slot() == null || !locked.slot().getReservation().getId().equals(r.getId())
                || !Objects.equals(locked.slot().getExpiresAt(), r.getExpiresAt())))
            throw new IllegalStateException("선점 슬롯이 현재 예약과 일치하지 않습니다.");
        var expected = em.createQuery("select s.seat.id from ReservationSeat s where s.reservation.id=:id order by s.seat.id", Long.class)
                .setParameter("id", r.getId()).getResultList();
        var owned = inventory.stream().filter(s -> s.getReservation() != null && s.getReservation().getId().equals(r.getId())).toList();
        if (expected.isEmpty() || !owned.stream().map(s -> s.getSeat().getId()).sorted().toList().equals(expected)
                || owned.stream().anyMatch(s -> s.getStatus() != status || (status == SeatStatus.HOLDING
                ? !Objects.equals(s.getHoldExpiredAt(), r.getExpiresAt()) : s.getHoldExpiredAt() != null)))
            throw new IllegalStateException("예약 좌석 연결이 일치하지 않습니다.");
        return owned;
    }

    private Payment payment(Long id) {
        return em.createQuery("select p from Payment p where p.reservation.id=:id", Payment.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);
    }

    public BookingResult cancel(Long userId, Long id, String key) {
        BookingIdempotency.key(key); holds.requireUser(userId);
        return operations.execute(userId, BookingOperationType.CANCEL_RESERVATION, key, id, holds.now(), 200,
                () -> cancelLocked(userId, id));
    }

    @Transactional(noRollbackFor = BookingRejection.class)
    ReservationResponse cancelLocked(Long userId, Long id) {
        var locked = lock(userId, id);
        var r = locked.reservation();
        if (r.getStatus() == ReservationStatus.CANCELLED) return holds.response(r, holds.now());
        if (r.getStatus() != ReservationStatus.PENDING && r.getStatus() != ReservationStatus.CONFIRMED)
            reject(409, "취소할 수 없는 예약 상태입니다.");
        if (r.getStatus() == ReservationStatus.PENDING && r.getExpiresAt() != null && !r.getExpiresAt().isAfter(holds.now())) {
            holds.expireLockedGroup(locked.group()); reject(409, "이미 만료된 선점입니다.");
        }
        if (!locked.show().getStartTime().isAfter(holds.now())) reject(409, "상영 시작 전까지만 취소할 수 있습니다.");
        var inventory = holds.lockInventory(locked.show().getId());
        var confirmed = r.getStatus() == ReservationStatus.CONFIRMED;
        var owned = validateInventory(locked, inventory, confirmed ? SeatStatus.RESERVED : SeatStatus.HOLDING);
        var payment = payment(id);
        var issued = em.createQuery("select t from Ticket t where t.reservation.id=:id", Ticket.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (confirmed && (payment == null || payment.getStatus() != PaymentStatus.SUCCESS || issued.size() != 1))
            throw new IllegalStateException("결제/티켓 상태가 일치하지 않아 취소하지 않았습니다.");
        if (!confirmed && (!issued.isEmpty() || payment != null && payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.FAILED))
            throw new IllegalStateException("미결제 예약의 결제/티켓 상태가 일치하지 않습니다.");
        if (issued.stream().anyMatch(t -> t.getStatus() != TicketStatus.VALID)) reject(409, "취소할 수 없는 티켓 상태입니다.");
        var now = holds.now();
        if (!locked.show().getStartTime().isAfter(now)) reject(409, "상영 시작 전까지만 취소할 수 있습니다.");
        if (!confirmed && (r.getExpiresAt() == null || !r.getExpiresAt().isAfter(now))) {
            holds.expireLockedGroup(locked.group()); reject(409, "이미 만료된 선점입니다.");
        }
        if (payment != null) { payment.setStatus(PaymentStatus.CANCELLED); payment.setUpdatedAt(now); }
        issued.forEach(t -> { t.setStatus(TicketStatus.CANCELLED); t.setUpdatedAt(now); });
        owned.forEach(s -> { s.setStatus(SeatStatus.AVAILABLE); s.setReservation(null); s.setHoldExpiredAt(null); });
        r.setStatus(ReservationStatus.CANCELLED); r.setUpdatedAt(now);
        locked.group().setStatus(BookingGroupStatus.CANCELLED); locked.group().setUpdatedAt(now);
        if (!confirmed && BookingQueueLifecycle.released(em, locked.group().getId(), true, now))
            locked.group().setStatus(BookingGroupStatus.ACTIVE);
        else BookingQueueLifecycle.cancelled(em, locked.group().getId(), now);
        if (locked.slot() != null) em.remove(locked.slot());
        BookingHoldService.updateAvailable(locked.show(), inventory, now);
        NotificationService.link(notifications.cancelled(userId), r);
        return holds.response(r, now);
    }

    private PaymentResponse paymentResponse(Reservation reservation, Payment payment, boolean issue) {
        if (issue && (payment == null || payment.getStatus() != PaymentStatus.SUCCESS))
            throw new IllegalStateException("확정 예약의 결제 내역을 확인할 수 없습니다.");
        var existing = issue ? List.<Ticket>of() : em.createQuery("select t from Ticket t where t.reservation.id=:id", Ticket.class)
                .setParameter("id", reservation.getId()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        return new PaymentResponse(payment == null ? null : payment.getId(), payment == null ? null : payment.getStatus(),
                payment == null ? null : payment.getAmount(), holds.response(reservation, holds.now()),
                issue ? tickets.issue(reservation.getUser().getId(), reservation.getId())
                        : existing.isEmpty() ? null : tickets.one(reservation.getUser().getId(), existing.getFirst().getId()));
    }

    public PaymentResponse get(Long userId, Long reservationId) {
        holds.requireUser(userId);
        try {
            var locked = lock(userId, reservationId);
            if (locked.slot() != null && locked.slot().getReservation().getId().equals(reservationId))
                holds.expireLockedGroup(locked.group());
            return paymentResponse(locked.reservation(), payment(reservationId), false);
        } catch (BookingRejection e) { throw new ResponseStatusException(HttpStatus.valueOf(e.status), e.getMessage()); }
    }
}
