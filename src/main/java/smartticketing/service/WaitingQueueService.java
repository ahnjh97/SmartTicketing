package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.WaitingQueue;
import smartticketing.entity.enums.QueueStatus;
import smartticketing.repository.WaitingQueueRepository;

import java.time.LocalDateTime;

@Service
@Transactional
public class WaitingQueueService {
    private static final int OPPORTUNITY_MINUTES = 5;

    private final WaitingQueueRepository queues;
    private final NotificationService notifications;
    private final EntityManager em;

    public WaitingQueueService(WaitingQueueRepository queues,
                               NotificationService notifications,
                               EntityManager em) {
        this.queues = queues;
        this.notifications = notifications;
        this.em = em;
    }

    /**
     * 대기열에서 차례가 된 사용자를 NOTIFIED 상태로 전환하고
     * 5분간 예매 기회를 부여한다.
     */
    public WaitingQueue notifyTurn(Long queueId) {
        WaitingQueue queue = queues.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("대기열을 찾을 수 없습니다."));

        if (queue.getRequestGroup() != null || queue.getStatus() != QueueStatus.WAITING) {
            return queue;
        }

        LocalDateTime now = LocalDateTime.now();
        queue.setStatus(QueueStatus.NOTIFIED);
        queue.setOpportunityExpiresAt(now.plusMinutes(OPPORTUNITY_MINUTES));
        queue.setUpdatedAt(now);

        return queue;
    }

    /**
     * 5분 예매 기회를 넘긴 대기열을 만료 처리한다.
     * 실제 대기열 승급은 좌석 확보/결제 로직에서 notifyTurn()을 호출한다.
     */
    public int expireOpportunities() {
        LocalDateTime now = LocalDateTime.now();
        var expired = em.createQuery("""
                select q from WaitingQueue q
                where q.status = :status
                  and q.requestGroup is null
                  and q.opportunityExpiresAt is not null
                  and q.opportunityExpiresAt <= :now
                """, WaitingQueue.class)
                .setParameter("status", QueueStatus.NOTIFIED)
                .setParameter("now", now)
                .getResultList();

        expired.forEach(q -> {
            q.setStatus(QueueStatus.EXPIRED);
            q.setUpdatedAt(now);
        });
        return expired.size();
    }
}
