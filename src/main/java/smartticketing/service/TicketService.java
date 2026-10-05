package smartticketing.service;

import smartticketing.dto.ticket.TicketResponse;
import smartticketing.dto.ticket.TicketVerifyResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.repository.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Transactional
public class TicketService {
    private static final String VERIFY_PROCESSING_PREFIX = "ticket:verify:processing:";
    private static final long VERIFY_DELAY_SECONDS = 2L;

    private final TicketRepository tickets;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository seats;
    private final NotificationService notifications;
    private final StringRedisTemplate redis;
    private final TransactionTemplate transactionTemplate;

    public TicketService(
            TicketRepository t,
            ReservationRepository r,
            ReservationSeatRepository s,
            NotificationService n,
            StringRedisTemplate redis,
            TransactionTemplate transactionTemplate
    ) {
        tickets = t;
        reservations = r;
        seats = s;
        notifications = n;
        this.redis = redis;
        this.transactionTemplate = transactionTemplate;
    }

    public TicketResponse issue(Long userId, Long reservationId) {
        // 기존 티켓 API도 결제/취소와 같은 예약 행에서 직렬화한다.
        // 이 경로는 상위 그룹/회차 잠금을 요청하지 않아 역순 잠금이 없다.
        Reservation r = reservations.findLockedById(reservationId).orElseThrow(() -> new IllegalArgumentException("예매를 찾을 수 없습니다."));
        if (!r.getUser().getId().equals(userId)) throw new IllegalStateException("본인의 예매만 티켓으로 발급할 수 있습니다.");
        if (r.getStatus() != ReservationStatus.CONFIRMED) throw new IllegalStateException("결제 완료된 예매만 티켓을 발급할 수 있습니다.");
        var old = tickets.findLockedByReservationId(reservationId);
        if (old.isPresent()) return to(old.get());
        Ticket t = new Ticket();
        t.setReservation(r);
        t.setTicketNumber("ST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
        t.setQrCode(t.getTicketNumber());
        t.setStatus(TicketStatus.VALID);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        Ticket saved = tickets.save(t);
        NotificationService.link(notifications.completed(userId), r);
        return to(saved);
    }

    public TicketVerifyResponse useNow(String qrCode) {
        if (qrCode == null || qrCode.isBlank()) return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        Ticket ticket = tickets.findByQrCode(qrCode).orElseGet(() -> tickets.findByTicketNumber(qrCode).orElse(null));
        if (ticket == null) return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        if (ticket.getStatus() == TicketStatus.USED) return new TicketVerifyResponse(false, false, "이미 사용된 티켓입니다.", to(ticket));
        if (ticket.getStatus() == TicketStatus.CANCELLED) return new TicketVerifyResponse(false, false, "취소된 티켓입니다.", to(ticket));
        if (ticket.getStatus() == TicketStatus.EXPIRED) return new TicketVerifyResponse(false, false, "만료된 티켓입니다.", to(ticket));
        int updated = tickets.markUsedIfValid(ticket.getQrCode(), TicketStatus.VALID, TicketStatus.USED, LocalDateTime.now());
        if (updated == 0) return new TicketVerifyResponse(false, false, "티켓 사용 처리에 실패했습니다.", to(ticket));
        ticket.setStatus(TicketStatus.USED);
        ticket.setUpdatedAt(LocalDateTime.now());
        return new TicketVerifyResponse(true, false, "사용 처리되었습니다.", to(ticket));
    }

    public TicketVerifyResponse verifyAndUse(String qrCode) {
        if (qrCode == null || qrCode.isBlank()) {
            return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        }

        Ticket ticket = tickets.findByQrCode(qrCode)
                .orElseGet(() -> tickets.findByTicketNumber(qrCode).orElse(null));

        if (ticket == null) {
            return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        }

        if (ticket.getStatus() == TicketStatus.USED) {
            return new TicketVerifyResponse(false, false, "이미 사용된 티켓입니다.", to(ticket));
        }

        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return new TicketVerifyResponse(false, false, "취소된 티켓입니다.", to(ticket));
        }
        if (ticket.getStatus() == TicketStatus.EXPIRED) {
            return new TicketVerifyResponse(false, false, "만료된 티켓입니다.", to(ticket));
        }

        String key = processingKey(ticket);
        Boolean alreadyProcessing = redis.hasKey(key);
        if (Boolean.TRUE.equals(alreadyProcessing)) {
            return new TicketVerifyResponse(false, true, "처리 중입니다.", to(ticket));
        }

        redis.opsForValue().set(key, "1", Duration.ofSeconds(VERIFY_DELAY_SECONDS + 2));

        CompletableFuture.delayedExecutor(VERIFY_DELAY_SECONDS, TimeUnit.SECONDS).execute(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    Ticket current = tickets.findByQrCode(ticket.getQrCode())
                            .orElseGet(() -> tickets.findByTicketNumber(ticket.getTicketNumber()).orElse(null));
                    if (current != null && current.getStatus() == TicketStatus.VALID) {
                        int updated = tickets.markUsedIfValid(
                                current.getQrCode(),
                                TicketStatus.VALID,
                                TicketStatus.USED,
                                LocalDateTime.now()
                        );
                        if (updated > 0) {
                            current.setStatus(TicketStatus.USED);
                            current.setUpdatedAt(LocalDateTime.now());
                        }
                    }
                    redis.delete(key);
                })
        );

        return new TicketVerifyResponse(false, true, "처리 중입니다.", to(ticket));
    }

    @Transactional(readOnly = true)
    public TicketVerifyResponse verifyStatus(String qrCode) {
        if (qrCode == null || qrCode.isBlank()) {
            return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        }

        Ticket ticket = tickets.findByQrCode(qrCode)
                .orElseGet(() -> tickets.findByTicketNumber(qrCode).orElse(null));

        if (ticket == null) {
            return new TicketVerifyResponse(false, false, "유효하지 않은 티켓입니다.", null);
        }

        boolean processing = Boolean.TRUE.equals(redis.hasKey(processingKey(ticket)));
        if (ticket.getStatus() == TicketStatus.USED) {
            return new TicketVerifyResponse(true, false, "사용 처리되었습니다.", to(ticket));
        }
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return new TicketVerifyResponse(false, false, "취소된 티켓입니다.", to(ticket));
        }
        if (ticket.getStatus() == TicketStatus.EXPIRED) {
            return new TicketVerifyResponse(false, false, "만료된 티켓입니다.", to(ticket));
        }
        if (processing) {
            return new TicketVerifyResponse(false, true, "처리 중입니다.", to(ticket));
        }
        return new TicketVerifyResponse(false, false, "사용 가능한 티켓입니다.", to(ticket));
    }

    private String processingKey(Ticket ticket) {
        return VERIFY_PROCESSING_PREFIX + ticket.getTicketNumber();
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> mine(Long userId) {
        return tickets.findByReservationUserIdOrderByCreatedAtDesc(userId).stream().map(this::to).toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse one(Long userId, Long ticketId) {
        Ticket t = tickets.findById(ticketId).orElseThrow(() -> new IllegalArgumentException("티켓을 찾을 수 없습니다."));
        if (!t.getReservation().getUser().getId().equals(userId))
            throw new IllegalStateException("본인의 티켓만 조회할 수 있습니다.");
        return to(t);
    }

    private TicketResponse to(Ticket t) {
        Reservation r = t.getReservation();
        var sh = r.getShowtime();
        var ss = seats.findByReservationId(r.getId()).stream().sorted(java.util.Comparator.comparing((ReservationSeat x) -> x.getSeat().getSeatRow()).thenComparing(x -> x.getSeat().getSeatNumber())).map(x -> x.getSeat().getSeatRow() + x.getSeat().getSeatNumber()).toList();
        return new TicketResponse(t.getId(), r.getId(), t.getTicketNumber(), t.getQrCode(), t.getStatus(), sh.getMovie().getTitle(), sh.getScreen().getTheater().getName(), sh.getScreen().getName(), sh.getStartTime(), sh.getEndTime(), ss, t.getCreatedAt(), r.getRequestGroup() == null ? null : r.getRequestGroup().getId());
    }
}
