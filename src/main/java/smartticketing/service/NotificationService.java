package smartticketing.service;

import smartticketing.dto.notification.NotificationResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.BookingEntryPoint;
import smartticketing.entity.enums.BookingGroupStatus;
import smartticketing.entity.enums.NotificationType;
import smartticketing.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
        var hidden = Set.of(NotificationType.SEAT_HOLD_STARTED, NotificationType.RESERVATION_COMPLETED, NotificationType.RESERVATION_CANCELLED);
        var l = unreadOnly ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId) : notifications.findByUserIdOrderByCreatedAtDesc(userId);
        return l.stream()
                .filter(n -> !hidden.contains(n.getType()))
                .map(n -> new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.isRead(), n.getCreatedAt(), n.getBookingGroupId(), n.getReservationId()))
                .toList();
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

    public static void holdExpired(jakarta.persistence.EntityManager em, Long groupId) {
        var turns = em.createQuery(
                "select n from Notification n where n.bookingGroupId=:group and n.type=:type",
                Notification.class)
                .setParameter("group", groupId)
                .setParameter("type", NotificationType.QUEUE_TURN)
                .getResultList();
        if (turns.isEmpty()) return;

        var user = turns.getFirst().getUser();
        turns.forEach(em::remove);
        em.createQuery("delete from Notification n where n.bookingGroupId=:group and n.type=:type")
                .setParameter("group", groupId)
                .setParameter("type", NotificationType.SEAT_HOLD_EXPIRED)
                .executeUpdate();

        var expired = new Notification();
        expired.setUser(user);
        expired.setType(NotificationType.SEAT_HOLD_EXPIRED);
        expired.setMessage("해당 회차의 좌석 선점 시간이 만료되었습니다. 다시 선점해주세요.");
        expired.setCreatedAt(LocalDateTime.now());
        expired.setBookingGroupId(groupId);
        em.persist(expired);
    }

    // 즉시선점이 확정된 스마트예매 배치에는 대기 순서 알림이 남아 있으면 안 된다.
    public static void clearQueueTurns(jakarta.persistence.EntityManager em, List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return;
        em.createQuery("delete from Notification n where n.bookingGroupId in :groups and n.type=:type")
                .setParameter("groups", groupIds)
                .setParameter("type", NotificationType.QUEUE_TURN)
                .executeUpdate();
    }

    public static Notification link(Notification notification, Reservation reservation) {
        if (reservation.getRequestGroup() != null) {
            notification.setBookingGroupId(reservation.getRequestGroup().getId());
            notification.setReservationId(reservation.getId());
        }
        return notification;
    }

    // 알림은 오직 "대기열 WAITING -> 실제 좌석 선점" 전환 직후에만 만든다.
    // 일반/스마트 즉시선점 경로에서는 이 메서드를 호출하지 않는다.
    public static void waitingAcquired(jakarta.persistence.EntityManager em, BookingRequestGroup group) {
        var reservation = em.createQuery("""
                select r from Reservation r
                where r.requestGroup.id=:group
                order by r.id desc
                """, Reservation.class)
                .setParameter("group", group.getId())
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (reservation == null) return;

        var notification = new Notification();
        notification.setUser(reservation.getUser());
        notification.setType(NotificationType.QUEUE_TURN);
        notification.setMessage("대기하던 좌석을 확보했습니다. 5분 안에 모의결제를 완료해주세요.");
        notification.setCreatedAt(reservation.getCreatedAt());
        em.persist(link(notification, reservation));
    }

    public Notification queueTurn(Long id) {
        return create(id, NotificationType.QUEUE_TURN, "대기하던 좌석을 확보했습니다. 5분 안에 모의결제를 완료해주세요.");
    }

    public Notification paymentFailed(Long id) {
        return create(id, NotificationType.PAYMENT_FAILED, "결제에 실패했습니다. 예매 상태를 확인해주세요.");
    }

    private static boolean hasActiveSmartHoldSibling(jakarta.persistence.EntityManager em, BookingRequestGroup group) {
        if (group == null || group.getEntryPoint() != BookingEntryPoint.THEATER_SMART) return false;
        return em.createQuery("""
                select count(g) from BookingRequestGroup g
                where g.user.id=:user
                  and g.movie.id=:movie
                  and g.viewingDate=:date
                  and g.entryPoint=:entry
                  and g.status=:status
                  and g.id<>:group
                  and g.createdAt=:createdAt
                """, Long.class)
                .setParameter("user", group.getUser().getId())
                .setParameter("movie", group.getMovie().getId())
                .setParameter("date", group.getViewingDate())
                .setParameter("entry", BookingEntryPoint.THEATER_SMART)
                .setParameter("status", BookingGroupStatus.HOLDING)
                .setParameter("group", group.getId())
                .setParameter("createdAt", group.getCreatedAt())
                .getSingleResult() > 0;
    }

    private Notification owned(Long userId, Long id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!n.getUser().getId().equals(userId)) throw new IllegalStateException("본인의 알림만 처리할 수 있습니다.");
        return n;
    }
}
