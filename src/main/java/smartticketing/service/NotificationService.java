package smartticketing.service;

import smartticketing.dto.notification.NotificationResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.NotificationType;
import smartticketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class NotificationService {
    private final NotificationRepository notifications;
    private final UsersRepository users;

    public NotificationService(NotificationRepository n, UsersRepository u) {
        notifications = n;
        users = u;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId, boolean unreadOnly) {
        var l = unreadOnly ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId) : notifications.findByUserIdOrderByCreatedAtDesc(userId);
        return l.stream().map(n -> new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.isRead(), n.getCreatedAt(), n.getBookingGroupId(), n.getReservationId())).toList();
    }

    public void read(Long userId, Long id) {
        owned(userId, id).setRead(true);
    }

    public void readAll(Long userId) {
        notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId).forEach(n -> n.setRead(true));
    }

    public void delete(Long userId, Long id) {
        notifications.delete(owned(userId, id));
    }

    public Notification create(Long userId, NotificationType type, String message) {
        Users u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        Notification n = new Notification();
        n.setUser(u);
        n.setType(type);
        n.setMessage(message);
        n.setCreatedAt(LocalDateTime.now());
        return notifications.save(n);
    }

    public Notification hold(Long id) {
        return create(id, NotificationType.SEAT_HOLD_STARTED, "좌석 5분 선점이 시작되었습니다. 제한 시간 내 결제를 완료해주세요.");
    }

    public static Notification link(Notification notification, Reservation reservation) {
        if (reservation.getRequestGroup() != null) {
            notification.setBookingGroupId(reservation.getRequestGroup().getId());
            notification.setReservationId(reservation.getId());
        }
        return notification;
    }

    // Called inside the same transaction as acquisition, including waiting dispatch.
    public static void acquired(jakarta.persistence.EntityManager em, Reservation reservation, boolean waiting) {
        var notification = new Notification();
        notification.setUser(reservation.getUser());
        notification.setType(waiting ? NotificationType.QUEUE_TURN : NotificationType.SEAT_HOLD_STARTED);
        notification.setMessage(waiting ? "대기하던 좌석을 확보했습니다. 5분 안에 모의결제를 완료해주세요." : "좌석을 5분간 확보했습니다. 예약에서 남은 시간을 확인해주세요.");
        notification.setCreatedAt(reservation.getCreatedAt());
        em.persist(link(notification, reservation));
    }

    public Notification queueTurn(Long id) {
        return create(id, NotificationType.QUEUE_TURN, "대기 순서가 되어 5분간 예매 기회가 시작되었습니다. 제한 시간 내 예매를 완료해주세요.");
    }

    public Notification completed(Long id) {
        return create(id, NotificationType.RESERVATION_COMPLETED, "영화 예매가 완료되었습니다.");
    }

    public Notification cancelled(Long id) {
        return create(id, NotificationType.RESERVATION_CANCELLED, "영화 예매가 취소되었습니다.");
    }

    public Notification paymentFailed(Long id) {
        return create(id, NotificationType.PAYMENT_FAILED, "결제에 실패했습니다. 예매 상태를 확인해주세요.");
    }

    private Notification owned(Long userId, Long id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!n.getUser().getId().equals(userId)) throw new IllegalStateException("본인의 알림만 처리할 수 있습니다.");
        return n;
    }
}
