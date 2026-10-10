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
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class BookingPaymentService {
    private final EntityManager em;
    private final BookingHoldService holds;
    private final BookingIdempotency operations;
    private final TicketService tickets;
    private final NotificationService notifications;
    private final boolean allowFailure;

    public BookingPaymentService(EntityManager em, BookingHoldService holds, BookingIdempotency operations,
            TicketService tickets, NotificationService notifications,
            Environment environment, @Value("${booking.mock-payment.allow-failure:false}") boolean allowFailure) {
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

    /** Creates a server-priced Toss order while the existing reservation hold is valid. */
    public TossOrderResponse createTossOrder(Long userId, Long reservationId) {
        holds.requireUser(userId);
        var locked = lock(userId, reservationId);
        var r = locked.reservation();
        if (r.getStatus() != ReservationStatus.PENDING) reject(409, "결제할 수 없는 예약 상태입니다.");
        if (r.getExpiresAt() == null || !r.getExpiresAt().isAfter(holds.now())) {
            holds.expireLockedGroup(locked.group());
            reject(409, "좌석 선점 시간이 만료되었습니다.");
        }
        BookingHoldService.validateShow(locked.show(), holds.now());
        var inventory = holds.lockSelectedInventory(locked.show().getId(), holds.reservationSeatIds(reservationId));
        validateInventory(locked, inventory, SeatStatus.HOLDING);
        var prices = em.createQuery("select s.price from ReservationSeat s where s.reservation.id=:id", Integer.class)
                .setParameter("id", reservationId).getResultList();
        if (prices.isEmpty() || prices.stream().anyMatch(p -> p == null || p <= 0)
                || r.getTotalAmount() == null || prices.stream().mapToLong(Integer::longValue).sum() != r.getTotalAmount())
            throw new IllegalStateException("저장된 예매 금액이 일치하지 않습니다.");
        var p = payment(reservationId);
        if (p != null && p.getPaymentMethod() != PaymentMethod.TOSS) {
            if (p.getPaymentMethod() == PaymentMethod.MOCK && p.getStatus() == PaymentStatus.FAILED) {
                p.setPaymentMethod(PaymentMethod.TOSS);
                p.setTossOrderId(null);
                p.setTossPaymentKey(null);
            } else {
                reject(409, "이미 다른 결제 방식으로 시작한 예약입니다.");
            }
        }
        if (p != null && p.getStatus() == PaymentStatus.SUCCESS)
            reject(409, "이미 결제가 완료된 예약입니다.");
        var retryOrder = p != null && p.getStatus() == PaymentStatus.FAILED;
        var now = holds.now();
        if (p == null) {
            p = new Payment();
            p.setReservation(r);
            p.setPaymentMethod(PaymentMethod.TOSS);
            p.setStatus(PaymentStatus.READY);
            p.setAmount(r.getTotalAmount());
            p.setCreatedAt(now);
            em.persist(p);
        } else if (retryOrder) {
            p.setStatus(PaymentStatus.READY);
            p.setTossPaymentKey(null);
        }
        if (p.getTossOrderId() == null || retryOrder) {
            p.setTossOrderId("st_" + UUID.randomUUID().toString().replace("-", ""));
        }
        p.setAmount(r.getTotalAmount());
        p.setUpdatedAt(now);
        return new TossOrderResponse(p.getTossOrderId(), r.getMovieTitle(), r.getTotalAmount());
    }

    /** Returns true when this exact Toss payment has already been finalized locally. */
    public boolean validateTossConfirmation(Long userId, Long reservationId, String orderId, Integer amount, String paymentKey) {
        holds.requireUser(userId);
        var locked = lock(userId, reservationId);
        var r = locked.reservation();
        var p = payment(reservationId);
        if (p == null || p.getPaymentMethod() != PaymentMethod.TOSS
                || !Objects.equals(p.getTossOrderId(), orderId)
                || !Objects.equals(p.getAmount(), amount)
                || !Objects.equals(r.getTotalAmount(), amount))
            reject(400, "주문번호 또는 결제 금액이 일치하지 않습니다.");
        if (r.getStatus() == ReservationStatus.CONFIRMED && p.getStatus() == PaymentStatus.SUCCESS
                && Objects.equals(p.getTossPaymentKey(), paymentKey)) return true;
        if (r.getStatus() != ReservationStatus.PENDING || p.getStatus() != PaymentStatus.READY)
            reject(409, "결제할 수 없는 예약 상태입니다.");
        if (r.getExpiresAt() == null || !r.getExpiresAt().isAfter(holds.now()))
            reject(409, "좌석 선점 시간이 만료되었습니다.");
        if (paymentKey == null || paymentKey.isBlank()) reject(400, "토스 결제 키가 없습니다.");
        return false;
    }

    /** Returns a paid Toss payment key only after verifying reservation ownership. */
    public String tossPaymentKeyForCancellation(Long userId, Long reservationId) {
        holds.requireUser(userId);
        var reservation = holds.ownedReservationForRead(userId, reservationId);
        var p = em.createQuery("select p from Payment p where p.reservation.id=:id", Payment.class)
                .setParameter("id", reservation.getId()).getResultStream().findFirst().orElse(null);
        if (p == null || p.getPaymentMethod() != PaymentMethod.TOSS || p.getStatus() != PaymentStatus.SUCCESS) return null;
        if (p.getTossPaymentKey() == null || p.getTossPaymentKey().isBlank())
            throw new IllegalStateException("토스 결제 키가 없어 안전하게 취소할 수 없습니다.");
        return p.getTossPaymentKey();
    }

    /** Called only after the server has confirmed the payment with Toss Payments. */
    public BookingResult payTossConfirmed(Long userId, Long reservationId, String key,
            String orderId, String paymentKey, Integer amount) {
        BookingIdempotency.key(key);
        holds.requireUser(userId);
        var request = new MockPaymentRequest(PaymentMethod.TOSS, false);
        return operations.execute(userId, BookingOperationType.CONFIRM_PAYMENT, key,
                new Intent(reservationId, request), holds.now(), () -> {
                    var p = payment(reservationId);
                    if (p == null || p.getPaymentMethod() != PaymentMethod.TOSS
                            || !Objects.equals(p.getTossOrderId(), orderId)
                            || !Objects.equals(p.getAmount(), amount))
                        reject(400, "토스 주문 정보가 일치하지 않습니다.");
                    if (p.getStatus() == PaymentStatus.SUCCESS) {
                        if (!Objects.equals(p.getTossPaymentKey(), paymentKey))
                            reject(409, "이미 다른 결제 정보로 승인된 예약입니다.");
                        var r = lock(userId, reservationId).reservation();
                        return paymentResponse(r, p, true);
                    }
                    p.setTossPaymentKey(paymentKey);
                    p.setUpdatedAt(holds.now());
                    return payLocked(userId, reservationId, request);
                });
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
        var inventory = holds.lockSelectedInventory(locked.show().getId(), holds.reservationSeatIds(id));
        var owned = validateInventory(locked, inventory, SeatStatus.HOLDING);
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
        if (payment != null && payment.getPaymentMethod() != request.paymentMethod()) {
            if (payment.getPaymentMethod() == PaymentMethod.TOSS && request.paymentMethod() == PaymentMethod.MOCK
                    && payment.getStatus() == PaymentStatus.READY && payment.getTossPaymentKey() == null) {
                // Closing an uncompleted Toss checkout must not disable the existing mock-payment path.
                payment.setPaymentMethod(PaymentMethod.MOCK);
                payment.setTossOrderId(null);
            } else {
                reject(409, "이미 다른 결제 방식으로 시작한 예약입니다.");
            }
        }
        if (payment != null && payment.getStatus() != PaymentStatus.FAILED && payment.getStatus() != PaymentStatus.READY)
            throw new IllegalStateException("결제와 예약 상태가 일치하지 않습니다.");
        var now = holds.now();
        BookingZoneLocks.finishGroup(em, locked.group().getId(), locked.show().getId());
        if (payment == null) {
            payment = new Payment(); payment.setReservation(r); payment.setCreatedAt(now); payment.setUpdatedAt(now);
            payment.setPaymentMethod(request.paymentMethod()); payment.setAmount(r.getTotalAmount()); em.persist(payment);
        }
        if (!Objects.equals(payment.getAmount(), r.getTotalAmount())) throw new IllegalStateException("결제 금액 불일치");
        payment.setUpdatedAt(now);
        if (request.simulateFailure()) {
            payment.setStatus(PaymentStatus.FAILED);
            BookingOutbox.append(em, locked.show().getId(), BookingOutboxEvent.Type.BOOKING_CHANGED, locked.group().getId(), "PAYMENT_FAILED", now);
            return paymentResponse(r, payment, false);
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        owned.forEach(seat -> { seat.setStatus(SeatStatus.RESERVED); seat.setHoldExpiredAt(null); });
        r.setStatus(ReservationStatus.CONFIRMED); r.setUpdatedAt(now);
        locked.group().setStatus(BookingGroupStatus.COMPLETED); locked.group().setUpdatedAt(now);
        BookingQueueLifecycle.completed(em, locked.group().getId(), now);
        em.remove(locked.slot());
        BookingOutbox.append(em, locked.show().getId(), BookingOutboxEvent.Type.BOOKING_CHANGED, locked.group().getId(), "PAYMENT_CONFIRMED", now);
        return paymentResponse(r, payment, true);
    }

    private Locked lock(Long userId, Long id) {
        var refs = em.createQuery("select r.requestGroup.id, r.showtime.id from Reservation r where r.id=:id and r.user.id=:user", Object[].class)
                .setParameter("id", id).setParameter("user", userId).getResultList();
        if (refs.isEmpty() || refs.getFirst()[0] == null) { reject(404, "예약을 찾을 수 없습니다."); }
        var ref = refs.getFirst();
        var group = holds.lockOwnedGroup(userId, (Long) ref[0]);
        var slot = group.getStatus() == BookingGroupStatus.HOLDING
                ? em.find(BookingGroupHold.class, group.getId(), LockModeType.PESSIMISTIC_WRITE) : null;
        BookingZoneLocks.lockGroup(em, group.getId(), (Long) ref[1],
                BookingZoneLocks.seatZones(em, (Long) ref[1], holds.reservationSeatIds(id)));
        var show = em.find(Showtime.class, (Long) ref[1]);
        em.refresh(show);
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
        var inventory = holds.lockSelectedInventory(locked.show().getId(), holds.reservationSeatIds(id));
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
        BookingZoneLocks.finishGroup(em, locked.group().getId(), locked.show().getId());
        holds.adjustAvailable(locked.show().getId(), BookingHoldService.availableReleased(locked.show(), owned), now);
        if (payment != null) { payment.setStatus(PaymentStatus.CANCELLED); payment.setUpdatedAt(now); }
        issued.forEach(t -> { t.setStatus(TicketStatus.CANCELLED); t.setUpdatedAt(now); });
        owned.forEach(s -> { s.setStatus(SeatStatus.AVAILABLE); s.setReservation(null); s.setHoldExpiredAt(null); });
        r.setStatus(ReservationStatus.CANCELLED); r.setUpdatedAt(now);
        locked.group().setStatus(BookingGroupStatus.CANCELLED); locked.group().setUpdatedAt(now);
        if (!confirmed && BookingQueueLifecycle.released(em, locked.group().getId(), true, now))
            locked.group().setStatus(BookingGroupStatus.ACTIVE);
        else BookingQueueLifecycle.cancelled(em, locked.group().getId(), now);
        if (locked.slot() != null) em.remove(locked.slot());

        BookingOutbox.append(em, locked.show().getId(), BookingOutboxEvent.Type.BOOKING_CHANGED, locked.group().getId(), "RESERVATION_CANCELLED", now);
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

    @Transactional(readOnly = true)
    public PaymentResponse get(Long userId, Long reservationId) {
        holds.requireUser(userId);
        return snapshot(userId, holds.ownedReservationForRead(userId, reservationId));
    }

    PaymentResponse snapshot(Long userId, Reservation reservation) {
        if(!reservation.getUser().getId().equals(userId))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var payment=em.createQuery("select p from Payment p where p.reservation.id=:id",Payment.class)
                .setParameter("id",reservation.getId()).getResultStream().findFirst().orElse(null);
        var ticket=em.createQuery("select t from Ticket t where t.reservation.id=:id",Ticket.class)
                .setParameter("id",reservation.getId()).getResultStream().findFirst().orElse(null);
        return new PaymentResponse(payment==null?null:payment.getId(),payment==null?null:payment.getStatus(),
                payment==null?null:payment.getAmount(),holds.snapshot(reservation,holds.now()),ticket==null?null:tickets.one(userId,ticket.getId()));
    }
}
