package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.entity.WaitingQueue;
import smartticketing.entity.enums.QueueStatus;
import java.util.*;

@Service
public class BookingWaitingProjection {
    private final EntityManager em;
    private final BookingWaitingRanks ranks;
    private final TransactionTemplate read;
    private record Snapshot(long version,List<BookingWaitingRanks.Entry> entries) {}
    public BookingWaitingProjection(EntityManager em,BookingWaitingRanks ranks,PlatformTransactionManager manager) {
        this.em=em; this.ranks=ranks; read=new TransactionTemplate(manager);
        read.setReadOnly(true); read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public void refresh(long show) {
        if(!ranks.enabled()) return;
        var snapshot=read.execute(status -> {
            long version=em.createQuery("select s.revision from BookingOutboxStream s where s.showtimeId=:show",Long.class)
                    .setParameter("show",show).getResultStream().findFirst().orElse(0L);
            var entries=em.createQuery("select q from WaitingQueue q where q.showtime.id=:show and q.requestGroup is not null and q.status=:waiting",WaitingQueue.class)
                    .setParameter("show",show).setParameter("waiting",QueueStatus.WAITING).getResultList().stream()
                    .map(q -> new BookingWaitingRanks.Entry(q.getId(),q.getSeatZone(),q.displayNumber())).toList();
            return new Snapshot(version,entries);
        });
        ranks.replace(show,snapshot.version(),snapshot.entries());
    }
    public List<Long> activeShows(long after,int limit) {
        return read.execute(status -> em.createQuery("select distinct q.showtime.id from WaitingQueue q where q.requestGroup is not null and q.showtime.id>:after and q.status in :states order by q.showtime.id",Long.class)
                .setParameter("after",after).setParameter("states",List.of(QueueStatus.WAITING,QueueStatus.PAUSED,QueueStatus.HOLDING))
                .setMaxResults(limit).getResultList());
    }
}
