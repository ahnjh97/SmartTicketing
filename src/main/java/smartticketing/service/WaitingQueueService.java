package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.WaitingQueue;
import smartticketing.entity.enums.QueueStatus;

import java.time.LocalDateTime;

@Service
@Transactional
public class WaitingQueueService {
    private final EntityManager em;

    public WaitingQueueService(EntityManager em) {
        this.em = em;
    }

    /** 기존 NOTIFIED 데이터의 만료 정리만 유지한다. 신규 배정은 BookingWaitingDispatcher가 담당한다. */
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
