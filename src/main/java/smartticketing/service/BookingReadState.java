package smartticketing.service;

import jakarta.persistence.EntityManager;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.time.LocalDateTime;

/** Read-time projection only. Never releases inventory or changes managed entities. */
final class BookingReadState {
    private BookingReadState() {}

    record Group(BookingGroupStatus status, BookingGroupHold activeHold, boolean expiredHold) {}

    static Group group(EntityManager em, BookingRequestGroup group, LocalDateTime now) {
        var hold = group.getStatus() == BookingGroupStatus.HOLDING
                ? em.find(BookingGroupHold.class, group.getId()) : null;
        boolean expired = hold != null && !hold.getExpiresAt().isAfter(now);
        return new Group(expired ? BookingGroupStatus.ACTIVE : group.getStatus(), expired ? null : hold, expired);
    }

    static QueueStatus queue(WaitingQueue queue, Group group, LocalDateTime now) {
        var status = queue.getStatus();
        if (status == QueueStatus.PAUSED && group.expiredHold()) status = QueueStatus.WAITING;
        if ((status == QueueStatus.WAITING || status == QueueStatus.PAUSED)
                && (!queue.getShowtime().getStartTime().isAfter(now)
                    || queue.getShowtime().getStatus() != ShowtimeStatus.SCHEDULED)) return QueueStatus.EXPIRED;
        if (status == QueueStatus.HOLDING && (group.expiredHold()
                || queue.getOpportunityExpiresAt() != null && !queue.getOpportunityExpiresAt().isAfter(now)))
            return QueueStatus.EXPIRED;
        return status;
    }
}
