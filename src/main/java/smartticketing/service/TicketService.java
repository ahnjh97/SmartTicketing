package smartticketing.service;

import smartticketing.dto.ticket.TicketResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class TicketService {
    private final TicketRepository tickets;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository seats;
    private final NotificationService notifications;

    public TicketService(TicketRepository t, ReservationRepository r, ReservationSeatRepository s, NotificationService n) {
        tickets = t;
        reservations = r;
        seats = s;
        notifications = n;
    }

    public TicketResponse issue(Long userId, Long reservationId) {
        Reservation r = reservations.findById(reservationId).orElseThrow(() -> new IllegalArgumentException("예매를 찾을 수 없습니다."));
        if (!r.getUser().getId().equals(userId)) throw new IllegalStateException("본인의 예매만 티켓으로 발급할 수 있습니다.");
        if (r.getStatus() != ReservationStatus.CONFIRMED) throw new IllegalStateException("결제 완료된 예매만 티켓을 발급할 수 있습니다.");
        var old = tickets.findByReservationId(reservationId);
        if (old.isPresent()) return to(old.get());
        Ticket t = new Ticket();
        t.setReservation(r);
        t.setTicketNumber("ST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase());
        t.setQrCode(t.getTicketNumber());
        t.setStatus(TicketStatus.VALID);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        Ticket saved = tickets.save(t);
        notifications.completed(userId);
        return to(saved);
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
        return new TicketResponse(t.getId(), r.getId(), t.getTicketNumber(), t.getQrCode(), t.getStatus(), sh.getMovie().getTitle(), sh.getScreen().getTheater().getName(), sh.getScreen().getName(), sh.getStartTime(), sh.getEndTime(), ss, t.getCreatedAt());
    }
}
