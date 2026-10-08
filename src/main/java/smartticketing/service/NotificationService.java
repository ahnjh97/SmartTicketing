package smartticketing.service;

import smartticketing.dto.notification.NotificationResponse;
import smartticketing.entity.*;
import smartticketing.entity.enums.NotificationType;
import smartticketing.repository.NotificationRepository;
import smartticketing.repository.UsersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        return notifications.findVisible(userId, USER_NOTIFICATION_TYPES, unreadOnly).stream().map(this::to).toList();
    }

    @Transactional(readOnly = true)
    public smartticketing.dto.common.CursorPage<NotificationResponse> page(Long userId, boolean unreadOnly, String cursor, int size) {
        var before = smartticketing.util.CreatedCursor.parse(cursor, size);
        var found = notifications.findVisiblePage(userId, USER_NOTIFICATION_TYPES, unreadOnly, before.time(), before.id(),
                org.springframework.data.domain.PageRequest.of(0, size + 1));
        var items = found.stream().limit(size).toList();
        String next = found.size() > size ? smartticketing.util.CreatedCursor.encode(items.getLast().getCreatedAt(), items.getLast().getId()) : null;
        return new smartticketing.dto.common.CursorPage<>(items.stream().map(this::to).toList(), next);
    }

    private NotificationResponse to(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.isRead(), n.getCreatedAt(),
                n.getBookingGroupId(), n.getReservationId());
    }

    public void read(Long userId, Long id) {
        owned(userId, id).setRead(true);
    }

    public void readAll(Long userId) {
        notifications.markVisibleRead(userId, USER_NOTIFICATION_TYPES);
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
        Notification saved = notifications.save(n);
        publishAfterCommit(userId);
        return saved;
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
        publishAfterCommit(reservation.getUser().getId());
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
        publishAfterCommit(user.getId());
    }

    /**
     * 알림 DB 반영이 실제 COMMIT된 뒤에만 SSE를 발행한다.
     * 그래야 브라우저가 이벤트를 받고 즉시 목록을 조회해도 새 알림을 반드시 읽을 수 있다.
     */
    private static void publishAfterCommit(Long userId) {
        if (userId == null) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            NotificationSseHub.publish(userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                NotificationSseHub.publish(userId);
            }
        });
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
