package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.test.util.ReflectionTestUtils;
import smartticketing.service.*;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import java.util.*;
import static smartticketing.booking.BookingWaitingTests.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingWaitingRedisTests {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static BookingWaitingRanks ranks;
    static String prefix;
    @BeforeAll static void start() throws Exception {
        db=new TemporaryMysqlDatabase(readStatements::add);
        connection=new LettuceConnectionFactory("127.0.0.1",6379); connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);
        try(var client=redis.getConnectionFactory().getConnection()) { assertThat(client.ping()).isEqualTo("PONG"); }
        ranks=new BookingWaitingRanks(redis,true,db.jdbcUrl());
        prefix="booking-ranks:v1:"+UUID.nameUUIDFromBytes(db.jdbcUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8))+":";
    }
    @AfterAll static void stop() throws Exception {
        if(redis!=null && prefix!=null) { var keys=redis.keys(prefix+"*"); if(keys!=null && !keys.isEmpty()) redis.delete(keys); }
        if(connection!=null) connection.destroy();
        if(db!=null) db.close();
    }
    JpaTransactionManager manager() {
        var manager=new JpaTransactionManager(db.factory());
        manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect()); return manager;
    }
    BookingWaitingProjection projection() {
        return new BookingWaitingProjection(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),ranks,manager());
    }
    smartticketing.dto.booking.WaitingResponse cachedState(Fixture f,BookingWaitingRanks cache) {
        return tx(em -> { var service=service(em,CLOCK); ReflectionTestUtils.setField(service,"ranks",cache); return service.get(f.user(),f.group()); });
    }
    @Test void cachedRanksMatchDbAndAvoidCountJoin() {
        var first=fixture(1,1); register(first); var second=another(first,1,false); register(second);
        for(var show:first.shows()) projection().refresh(show);
        var expected=state(second);
        readStatements.clear();
        var actual=cachedState(second,ranks);
        assertThat(actual.items()).isEqualTo(expected.items());
        assertThat(actual.items()).allMatch(i -> i.aheadCount()==1);
        assertThat(readStatements).noneMatch(s -> s.contains("count(") && s.contains("waiting_queues"));
    }
    @Test void versionMismatchAndRedisLossFallBackThenRebuild() {
        var first=fixture(1,1); register(first); var second=another(first,1,false); register(second);
        for(var show:first.shows()) projection().refresh(show);
        tx(em -> service(em,CLOCK).cancel(first.user(),first.group(),key()));
        assertThat(cachedState(second,ranks).items()).isEqualTo(state(second).items());
        var worker=new BookingWaitingProjectionWorker(projection(),new AdminMaintenanceGate());
        worker.repair();
        redis.delete(redis.keys(prefix+"*"));
        assertThat(cachedState(second,ranks).items()).isEqualTo(state(second).items());
        worker.repair();
        readStatements.clear(); cachedState(second,ranks);
        assertThat(readStatements).noneMatch(s -> s.contains("count(") && s.contains("waiting_queues"));
    }
    @Test void redisFailureFallsBackWithoutFailingRequest() {
        var f=fixture(1,1); register(f);
        var unavailable=mock(StringRedisTemplate.class);
        when(unavailable.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class)))
                .thenThrow(new IllegalStateException("offline"));
        var cache=new BookingWaitingRanks(unavailable,true,"isolated-failure");
        assertThat(cachedState(f,cache).items()).isEqualTo(state(f).items());
    }
    @Test void atomicReplacementRejectsOldVersionsAndDetectsPartialEviction() {
        var zone=SeatPosition.MIDDLE_MIDDLE;
        var a=new BookingWaitingRanks.Entry(1,zone,1); var b=new BookingWaitingRanks.Entry(2,zone,2);
        long show=990001;
        ranks.replace(show,2,List.of(b)); ranks.replace(show,1,List.of(a,b));
        assertThat(ranks.read(show,2,List.of(b))).containsEntry(2L,0L);
        assertThat(ranks.read(show,1,List.of(b))).isEmpty();
        redis.delete(prefix+"{"+show+"}:"+zone.name());
        assertThat(ranks.read(show,2,List.of(b))).isEmpty();
        ranks.replace(show,2,List.of(a,b));
        assertThat(ranks.read(show,2,List.of(b))).containsEntry(2L,1L);
    }
    @Test void rankCountsStrictlyEarlierNumbersAndSeparatesZonesAndNullZone() {
        var zone=SeatPosition.MIDDLE_MIDDLE;
        var rows=List.of(new BookingWaitingRanks.Entry(1,zone,1),new BookingWaitingRanks.Entry(2,zone,1),
                new BookingWaitingRanks.Entry(3,zone,2),new BookingWaitingRanks.Entry(4,null,1),
                new BookingWaitingRanks.Entry(5,SeatPosition.SIDE_FRONT,1));
        ranks.replace(990002,1,rows);
        assertThat(ranks.read(990002,1,rows)).containsAllEntriesOf(Map.of(1L,0L,2L,0L,3L,2L,4L,0L,5L,0L));
    }
    @Test void outboxAloneAllocatesAndReplayDoesNotAllocateTwice() {
        var f=fixture(1,1); register(f);
        var handler=new BookingWaitingOutboxHandler(dispatcher(CLOCK),projection(),true);
        var event=tx(e -> e.createQuery("select o from BookingOutboxEvent o where o.groupId=:g order by o.id",BookingOutboxEvent.class)
                .setParameter("g",f.group()).getResultList().getFirst());
        var delivery=new BookingOutboxStore.Delivery(event.getId(),event.getShowtimeId(),event.getAggregateVersion(),1,event.getEventType(),f.group(),event.getReason(),NOW,1,"test");
        handler.handle(delivery); handler.handle(delivery);
        statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
        long count=tx(e -> e.createQuery("select count(r) from Reservation r where r.requestGroup.id=:g",Long.class).setParameter("g",f.group()).getSingleResult());
        assertThat(count).isEqualTo(1);
        for(var show:f.shows()) projection().refresh(show);
        assertThat(cachedState(f,ranks).items()).isEqualTo(state(f).items());
    }
    @Test void redisFailureDoesNotPreventAllocationAndPropagatesForOutboxRetry() {
        var f=fixture(1,1); register(f);
        var broken=mock(BookingWaitingProjection.class); doThrow(new IllegalStateException("offline")).when(broken).refresh(anyLong());
        var handler=new BookingWaitingOutboxHandler(dispatcher(CLOCK),broken,true);
        var delivery=new BookingOutboxStore.Delivery(1L,f.shows().getFirst(),1,1,BookingOutboxEvent.Type.WAITING_CHANGED,f.group(),"test",NOW,1,"test");
        assertThatThrownBy(() -> handler.handle(delivery)).isInstanceOf(IllegalStateException.class);
        statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
    }
    @Test void workerConsumesCommittedEventsAndAcknowledgesSuccessfulProjection() {
        // Isolate the worker from events created by other test scenarios.
        tx(em -> { em.createQuery("delete from BookingOutboxEvent").executeUpdate(); return null; });
        var f=fixture(1,1); register(f);
        var store=new BookingOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),manager());
        var handler=new BookingWaitingOutboxHandler(dispatcher(CLOCK),projection(),true);
        var worker=new BookingOutboxWorker(store,List.of(handler),new AdminMaintenanceGate(),CLOCK);
        for(int i=0;i<5;i++) worker.recover();
        statuses(f,QueueStatus.HOLDING,QueueStatus.PAUSED);
        var states=tx(em -> em.createQuery("select e.status from BookingOutboxEvent e where e.groupId=:g",BookingOutboxEvent.Status.class)
                .setParameter("g",f.group()).getResultList());
        assertThat(states).isNotEmpty().containsOnly(BookingOutboxEvent.Status.COMPLETED);
        readStatements.clear(); cachedState(f,ranks);
        assertThat(readStatements).noneMatch(s -> s.contains("count(") && s.contains("waiting_queues"));
    }

    @Test void busyShowDoesNotReadDbOrAcknowledgeEventAndRetriesAfterRelease() {
        tx(em -> { em.createQuery("delete from BookingOutboxEvent").executeUpdate(); return null; });
        var f=fixture(1,1);
        register(f,List.of(f.shows().getFirst()),key());
        var dispatcher=dispatcher(CLOCK);
        var gate=new BookingDispatchGate(redis,true,30000,db.jdbcUrl());
        ReflectionTestUtils.setField(dispatcher,"dispatchGate",gate);
        var store=new BookingOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),manager());
        var handler=new BookingWaitingOutboxHandler(dispatcher,projection(),true);
        var worker=new BookingOutboxWorker(store,List.of(handler),new AdminMaintenanceGate(),CLOCK);
        gate.run(f.shows().getFirst(),() -> {
            readStatements.clear();
            assertThatThrownBy(() -> dispatcher.dispatch(f.shows().getFirst())).isInstanceOf(BookingDispatchGate.Busy.class);
            assertThat(readStatements).isEmpty();
            new BookingWaitingWorker(dispatcher,new AdminMaintenanceGate()).sweep();
            worker.recover();
            return 0;
        });
        statuses(f,QueueStatus.WAITING);
        var events=tx(em -> em.createQuery("select e from BookingOutboxEvent e where e.groupId=:g",BookingOutboxEvent.class)
                .setParameter("g",f.group()).getResultList());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getStatus()).isEqualTo(BookingOutboxEvent.Status.PENDING);
        assertThat(events.getFirst().getAttempts()).isEqualTo(1);
        var retry=new BookingOutboxWorker(store,List.of(handler),new AdminMaintenanceGate(),java.time.Clock.offset(CLOCK,java.time.Duration.ofSeconds(3)));
        for(int i=0;i<3;i++) retry.recover();
        statuses(f,QueueStatus.HOLDING);
    }
}
