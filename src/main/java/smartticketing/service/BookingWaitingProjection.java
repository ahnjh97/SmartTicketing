package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.entity.enums.QueueStatus;
import smartticketing.entity.enums.SeatPosition;
import java.util.*;

@Service
public class BookingWaitingProjection {
    private final EntityManager em;
    private final BookingWaitingRanks ranks;
    private final TransactionTemplate read;
    private record Snapshot(long version,List<BookingWaitingRanks.Entry> entries) {}
    private record Delta(long version,List<BookingWaitingRanks.Entry> upserts,List<Long> removals,boolean rebuild) {}
    // Bound recovery work after a long outage; normal events are coalesced by group below.
    private static final int MAX_DELTA_EVENTS = 1000;
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
            var entries=em.createQuery("select q.id,q.seatZone,coalesce(q.zoneQueueNumber,q.queueNumber) from WaitingQueue q where q.showtime.id=:show and q.requestGroup is not null and q.status=:waiting",Object[].class)
                    .setParameter("show",show).setParameter("waiting",QueueStatus.WAITING).getResultList().stream()
                    .map(q -> new BookingWaitingRanks.Entry((Long)q[0],(SeatPosition)q[1],(Integer)q[2])).toList();
            return new Snapshot(version,entries);
        });
        ranks.replace(show,snapshot.version(),snapshot.entries());
    }
    /** Catch up all committed changes, including events produced by the dispatcher just now. */
    public void update(long show) {
        if(!ranks.enabled()) return;
        Long base=ranks.version(show);
        if(base==null) { refresh(show); return; }
        var delta=read.execute(status -> {
            long version=em.createQuery("select s.revision from BookingOutboxStream s where s.showtimeId=:show",Long.class)
                    .setParameter("show",show).getResultStream().findFirst().orElse(0L);
            if(version<=base) return new Delta(version,List.of(),List.of(),false);
            if(version-base>MAX_DELTA_EVENTS) return new Delta(version,List.of(),List.of(),true);
            // Include completed events too: out-of-order delivery/ack is not projection ordering.
            // Version and changed rows come from one REPEATABLE_READ snapshot, never mixed commits.
            var events=em.createQuery("select e.aggregateVersion,e.groupId,e.schemaVersion from BookingOutboxEvent e where e.showtimeId=:show and e.aggregateVersion>:base and e.aggregateVersion<=:version order by e.aggregateVersion",Object[].class)
                    .setParameter("show",show).setParameter("base",base).setParameter("version",version)
                    .setMaxResults(MAX_DELTA_EVENTS).getResultList();
            long expected=base;
            var groups=new LinkedHashSet<Long>();
            for(var event:events) {
                if((Long)event[0]!=++expected || event[1]==null || (Integer)event[2]!=1)
                    return new Delta(version,List.of(),List.of(),true);
                groups.add((Long)event[1]);
            }
            if(expected!=version) return new Delta(version,List.of(),List.of(),true);
            var upserts=new ArrayList<BookingWaitingRanks.Entry>();
            var removals=new ArrayList<Long>();
            var ids=new ArrayList<>(groups);
            for(int start=0;start<ids.size();start+=100) {
                var rows=em.createQuery("select q.id,q.seatZone,coalesce(q.zoneQueueNumber,q.queueNumber),q.status from WaitingQueue q where q.showtime.id=:show and q.requestGroup.id in :groups",Object[].class)
                        .setParameter("show",show).setParameter("groups",ids.subList(start,Math.min(start+100,ids.size()))).getResultList();
                for(var row:rows) {
                    if(row[3]==QueueStatus.WAITING) upserts.add(new BookingWaitingRanks.Entry((Long)row[0],(SeatPosition)row[1],(Integer)row[2]));
                    else removals.add((Long)row[0]);
                }
            }
            return new Delta(version,upserts,removals,false);
        });
        // A parallel worker may already have published a newer snapshot. Never roll it back.
        if(delta.version()<=base) return;
        if(delta.rebuild()) { refresh(show); return; }
        if(!ranks.patch(show,base,delta.version(),delta.upserts(),delta.removals())) {
            Long current=ranks.version(show);
            if(current==null || current<delta.version()) refresh(show);
        }
    }
    public void repairIfNeeded(long show) {
        // The recovery sweep can catch up a normal lag without rebuilding all WAITING rows.
        update(show);
    }
    public List<Long> activeShows(long after,int limit) {
        return read.execute(status -> em.createQuery("select distinct q.showtime.id from WaitingQueue q where q.requestGroup is not null and q.showtime.id>:after and q.status in :states order by q.showtime.id",Long.class)
                .setParameter("after",after).setParameter("states",List.of(QueueStatus.WAITING,QueueStatus.PAUSED,QueueStatus.HOLDING))
                .setMaxResults(limit).getResultList());
    }
}
