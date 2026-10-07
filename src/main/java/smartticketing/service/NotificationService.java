package smartticketing.service;

import smartticketing.dto.notification.NotificationResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.NotificationType;
import smartticketing.repository.NotificationRepository;
import smartticketing.repository.UsersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class NotificationService {
    private static final Set<NotificationType> USER_NOTIFICATION_TYPES =
            Set.of(NotificationType.QUEUE_TURN, NotificationType.SEAT_HOLD_EXPIRED);

    private final NotificationRepository notifications;
    private final UsersRepository users;

    public NotificationService(NotificationRepository n, UsersRepository u) {
        notifications = n;
        users = u;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId, boolean unreadOnly) {
        var l = unreadOnly
                ? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId)
                : notifications.findByUserIdOrderByCreatedAtDesc(userId);
        return l.stream()
                .filter(n -> USER_NOTIFICATION_TYPES.contains(n.getType()))
                .map(n -> new NotificationResponse(
                        n.getId(), n.getType(), n.getMessage(), n.isRead(), n.getCreatedAt(),
                        n.getBookingGroupId(), n.getReservationId()))
                .toList();
    }

    public void read(Long userId, Long id) {
        owned(userId, id).setRead(true);
    }

    public void readAll(Long userId) {
        notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId).stream()
                .filter(n -> USER_NOTIFICATION_TYPES.contains(n.getType()))
                .forEach(n -> n.setRead(true));
    }

    public void delete(Long userId, Long id) {
        notifications.delete(owned(userId, id));
    }

    /** Generic persistence helper. New application flows must only create QUEUE_TURN or SEAT_HOLD_EXPIRED. */
    public Notification create(Long userId, NotificationType type, String message) {
        Users u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        Notification n = new Notification();
        n.setUser(u);
        n.setType(type);
        n.setMessage(message);
        n.setCreatedAt(LocalDateTime.now());
        return notifications.save(n);
    }

    /**
     * WAITING -> HOLDING 승급이 실제로 완료된 뒤에만 호출한다.
     */
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
        notification.setCreatedAt(LocalDateTime.now());
        em.persist(link(notification, reservation));
    }

    /**
     * QUEUE_TURN이 붙은 실제 선점이 5분을 넘겨 만료된 경우에만 생성한다.
     * 기존 QUEUE_TURN은 제거하고 만료 알림 하나로 교체한다.
     */
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

    /** 즉시선점 경로에서 혹시 남은 QUEUE_TURN이 있으면 제거한다. */
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

    private Notification owned(Long userId, Long id) {
        Notification n = notifications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!n.getUser().getId().equals(userId)) {
            throw new IllegalStateException("본인의 알림만 처리할 수 있습니다.");
        }
        return n;
    }
}
