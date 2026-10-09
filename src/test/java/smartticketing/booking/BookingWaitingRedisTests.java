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
    @Test void registrationUsesOneGroupedCountFallbackInsteadOfOneCountPerQueueRow() {
        var f=fixture(1,1);
        readStatements.clear();
        tx(em -> {
            var service=service(em,CLOCK);
            ReflectionTestUtils.setField(service,"ranks",ranks);
            return service.register(f.user(),f.group(),key(),new smartticketing.dto.booking.WaitingRequest(f.shows())).status();
        });
        var countQueries=readStatements.stream()
                .filter(sql -> sql.toLowerCase(java.util.Locale.ROOT).contains("count(")
                        && sql.toLowerCase(java.util.Locale.ROOT).contains("waiting_queues"))
                .toList();
        assertThat(countQueries).hasSizeLessThanOrEqualTo(1);
    }

    @Test void disabledRanksUseDbAndProjectionDoesNotContactRedis() {
        var first=fixture(1,1); register(first); var second=another(first,1,false); register(second);
        var unused=org.mockito.Mockito.mock(StringRedisTemplate.class);
        var disabled=new BookingWaitingRanks(unused,false,db.jdbcUrl());
        var projection=new BookingWaitingProjection(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),disabled,manager());
        projection.refresh(first.shows().getFirst());
        projection.repairIfNeeded(first.shows().getFirst());
        var expected=state(second);
        readStatements.clear();
        assertThat(cachedState(second,disabled).items()).isEqualTo(expected.items());
        assertThat(readStatements).anyMatch(sql -> sql.contains("count(") && sql.contains("waiting_queues"));
        org.mockito.Mockito.verifyNoInteractions(unused);
    }

    @Test void warmRegistrationAvoidsCountWithoutPublishingUncommittedRanks() {
        var first=fixture(1,1); register(first);
        first.shows().forEach(show->projection().refresh(show));
        var versions=new HashMap<Long,Long>(); first.shows().forEach(show->versions.put(show,ranks.version(show)));
        var second=another(first,1,false);
        readStatements.clear();
        var result=tx(em->{
            var service=service(em,CLOCK); ReflectionTestUtils.setField(service,"ranks",ranks);
            return service.register(second.user(),second.group(),key(),new smartticketing.dto.booking.WaitingRequest(second.shows()));
        });
        assertThat(result.status()).isEqualTo(201);
        var response=tools.jackson.databind.json.JsonMapper.builder().build().readValue(result.body(),smartticketing.dto.booking.WaitingResponse.class);
        assertThat(response.items()).allMatch(item->item.aheadCount()==1);
        assertThat(readStatements).noneMatch(sql->sql.contains("count(") && sql.contains("waiting_queues"));
        first.shows().forEach(show->assertThat(ranks.version(show)).isEqualTo(versions.get(show)));
        assertThat(response.items()).usingRecursiveComparison()
                .withComparatorForType(Comparator.comparing(java.time.OffsetDateTime::toInstant),java.time.OffsetDateTime.class)
                .isEqualTo(state(second).items());
    }

    @Test void pendingRankOverlayRollsBackWithoutLeakingIntoRedis() {
        var first=fixture(1,1); register(first); first.shows().forEach(show->projection().refresh(show));
        var second=another(first,1,false); long show=first.shows().getFirst(); long version=ranks.version(show);
        try(var em=db.open()) {
            em.getTransaction().begin();
            try {
                var service=service(em,CLOCK); ReflectionTestUtils.setField(service,"ranks",ranks);
                readStatements.clear();
                assertThat(service.register(second.user(),second.group(),key(),new smartticketing.dto.booking.WaitingRequest(second.shows())).status()).isEqualTo(201);
                assertThat(readStatements).noneMatch(sql->sql.contains("count(") && sql.contains("waiting_queues"));
            } finally { em.getTransaction().rollback(); }
        }
        assertThat(ranks.version(show)).isEqualTo(version);
        assertThat(redis.opsForZSet().zCard(prefix+"{"+show+"}:MIDDLE_MIDDLE")).isEqualTo(2);
        assertThat(state(second).items()).isEmpty();
    }

    @Test void warmZoneChangeReturnsDestinationRankWithoutCountOrSharedCacheMutation() {
        var first=fixture(1,2); long show=first.shows().getFirst();
        tx(em->{inventory(em,show).getLast().getSeat().setSeatPosition(SeatPosition.SIDE_FRONT);return null;});
        register(first);
        var second=another(first,1,false);
        tx(em->service(em,CLOCK).register(second.user(),second.group(),key(),new smartticketing.dto.booking.WaitingRequest(List.of(show),SeatPosition.SIDE_FRONT)));
        first.shows().forEach(id->projection().refresh(id)); long base=ranks.version(show);
        readStatements.clear();
        var result=tx(em->{
            var service=service(em,CLOCK); ReflectionTestUtils.setField(service,"ranks",ranks);
            return service.register(first.user(),first.group(),key(),new smartticketing.dto.booking.WaitingRequest(List.of(show),SeatPosition.SIDE_FRONT));
        });
        assertThat(result.status()).as(result.body()).isEqualTo(201);
        assertThat(readStatements).noneMatch(sql->sql.contains("count(") && sql.contains("waiting_queues"));
        var response=tools.jackson.databind.json.JsonMapper.builder().build().readValue(result.body(),smartticketing.dto.booking.WaitingResponse.class);
        var changed=response.items().stream().filter(item->item.showtimeId()==show).findFirst().orElseThrow();
        assertThat(changed.seatZone()).isEqualTo(SeatPosition.SIDE_FRONT);
        assertThat(changed.aheadCount()).isEqualTo(1);
        assertThat(ranks.version(show)).isEqualTo(base);
        assertThat(response.items()).usingRecursiveComparison()
                .withComparatorForType(Comparator.comparing(java.time.OffsetDateTime::toInstant),java.time.OffsetDateTime.class)
                .isEqualTo(state(first).items());
    }

    @Test void rankOverlayAccountsForMovesRemovalsAndEqualNumbersWithoutChangingRedis() {
        long show=990010; var middle=SeatPosition.MIDDLE_MIDDLE; var side=SeatPosition.SIDE_FRONT;
        ranks.replace(show,1,List.of(new BookingWaitingRanks.Entry(1,middle,1),new BookingWaitingRanks.Entry(2,middle,3),new BookingWaitingRanks.Entry(3,side,2)));
        var requests=List.of(new BookingWaitingRanks.Entry(90,middle,10),new BookingWaitingRanks.Entry(91,side,10),
                new BookingWaitingRanks.Entry(92,middle,4),new BookingWaitingRanks.Entry(93,side,5));
        var changes=List.of(new BookingWaitingRanks.Entry(1,side,5),new BookingWaitingRanks.Entry(4,middle,4));
        assertThat(ranks.readAdjusted(show,1,requests,changes,List.of(2L))).containsExactlyInAnyOrderEntriesOf(Map.of(90L,1L,91L,2L,92L,0L,93L,1L));
        assertThat(ranks.version(show)).isEqualTo(1);
        assertThat(redis.opsForZSet().score(prefix+"{"+show+"}:MIDDLE_MIDDLE","1")).isEqualTo(1d);
        assertThat(ranks.readAdjusted(show,2,requests,changes,List.of(2L))).isEmpty();
        redis.delete(prefix+"{"+show+"}:SIDE_REAR");
        assertThat(ranks.readAdjusted(show,1,requests,changes,List.of(2L))).isEmpty();
    }

    @Test void registrationWithMissingDeltaHistoryUsesOneGroupedCountAndCorrectRanks() {
        var first=fixture(1,1); register(first); first.shows().forEach(show->projection().refresh(show));
        var second=another(first,1,false); register(second);
        tx(em->{em.createQuery("delete from BookingOutboxEvent e where e.groupId=:g").setParameter("g",second.group()).executeUpdate();return null;});
        var third=another(first,1,false);
        readStatements.clear();
        var result=tx(em->{
            var service=service(em,CLOCK); ReflectionTestUtils.setField(service,"ranks",ranks);
            return service.register(third.user(),third.group(),key(),new smartticketing.dto.booking.WaitingRequest(third.shows()));
        });
        assertThat(result.status()).isEqualTo(201);
        assertThat(readStatements.stream().filter(sql->sql.contains("count(") && sql.contains("waiting_queues"))).hasSize(1);
        var response=tools.jackson.databind.json.JsonMapper.builder().build().readValue(result.body(),smartticketing.dto.booking.WaitingResponse.class);
        assertThat(response.items()).allMatch(item->item.aheadCount()==2);
    }

    @Test void projectionPublishesBeforeBusyDispatchAndAfterSuccessfulAllocation() {
        var dispatcher=mock(BookingWaitingDispatcher.class); var projection=mock(BookingWaitingProjection.class);
        var handler=new BookingWaitingOutboxHandler(dispatcher,projection,true);
        var event=new BookingOutboxStore.Delivery(1L,123L,1,1,BookingOutboxEvent.Type.WAITING_CHANGED,1L,"test",NOW,1,"test");
        handler.handle(event);
        var order=inOrder(projection,dispatcher);
        order.verify(projection).update(123L); order.verify(dispatcher).dispatch(123L); order.verify(projection).update(123L);
        reset(projection,dispatcher);
        when(dispatcher.dispatch(123L)).thenThrow(new IllegalStateException("busy"));
        assertThatThrownBy(()->handler.handle(event)).isInstanceOf(IllegalStateException.class);
        verify(projection).update(123L);
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

    @Test void healthyProjectionRepairSkipsQueueRowsAndRedisRewrite() {
        var f=fixture(1,1); register(f); long show=f.shows().getFirst();
        projection().refresh(show);
        var versionKey=prefix+"{"+show+"}:version";
        redis.expire(versionKey,java.time.Duration.ofSeconds(30));
        readStatements.clear(); projection().repairIfNeeded(show);
        assertThat(readStatements).noneMatch(sql -> sql.contains("waiting_queues"));
        assertThat(redis.getExpire(versionKey)).isBetween(1L,30L);
    }

    @Test void incrementalCatchupReadsOnlyChangedGroupsAndReplayReadsNoQueueRows() {
        var first=fixture(1,1); register(first);
        long show=first.shows().getFirst(); projection().refresh(show);
        var second=another(first,1,false); register(second);
        readStatements.clear(); projection().update(show);
        var reads=List.copyOf(readStatements).stream().filter(sql->sql.contains("waiting_queues")).toList();
        assertThat(reads).hasSize(1).allMatch(sql->sql.contains("request_group_id in"));
        assertThat(cachedState(second,ranks).items()).isEqualTo(state(second).items());
        readStatements.clear(); projection().update(show);
        assertThat(readStatements).noneMatch(sql->sql.contains("waiting_queues"));
        tx(em->service(em,CLOCK).cancel(first.user(),first.group(),key()));
        readStatements.clear(); projection().repairIfNeeded(show);
        assertThat(readStatements.stream().filter(sql->sql.contains("waiting_queues")))
                .isNotEmpty().allMatch(sql->sql.contains("request_group_id in"));
        long version=ranks.version(show);
        var remaining=tx(em->em.createQuery("select q from WaitingQueue q where q.requestGroup.id=:g and q.showtime.id=:s",WaitingQueue.class)
                .setParameter("g",second.group()).setParameter("s",show).getSingleResult());
        assertThat(ranks.read(show,version,List.of(new BookingWaitingRanks.Entry(remaining.getId(),remaining.getSeatZone(),remaining.displayNumber()))))
                .containsEntry(remaining.getId(),0L);
    }

    @Test void missingHistoryAndMaintenanceEventsRebuildFromAuthoritativeState() {
        var first=fixture(1,1); register(first); long show=first.shows().getFirst();
        projection().refresh(show); long base=ranks.version(show);
        var second=another(first,1,false); register(second);
        tx(em->{ em.createQuery("delete from BookingOutboxEvent e where e.showtimeId=:s and e.aggregateVersion>:v")
                .setParameter("s",show).setParameter("v",base).executeUpdate(); return null; });
        readStatements.clear(); projection().update(show);
        assertThat(readStatements).anyMatch(sql->sql.contains("waiting_queues") && sql.contains("request_group_id is not null"));
        assertThat(cachedState(second,ranks).items()).isEqualTo(state(second).items());
        tx(em->{ BookingOutbox.append(em,show,BookingOutboxEvent.Type.SHOWTIME_CHANGED,null,"MAINTENANCE",NOW); return null; });
        readStatements.clear(); projection().update(show);
        assertThat(readStatements).anyMatch(sql->sql.contains("waiting_queues") && sql.contains("request_group_id is not null"));
    }

    @Test void atomicPatchMovesZonesRemovesRowsRejectsStaleWritersAndPreservesRepairTtl() {
        long show=990003; var middle=SeatPosition.MIDDLE_MIDDLE; var side=SeatPosition.SIDE_FRONT;
        var a=new BookingWaitingRanks.Entry(1,middle,1); var b=new BookingWaitingRanks.Entry(2,middle,2);
        ranks.replace(show,1,List.of(a,b));
        var versionKey=prefix+"{"+show+"}:version";
        redis.expire(versionKey,java.time.Duration.ofSeconds(30));
        assertThat(ranks.patch(show,1,3,List.of(new BookingWaitingRanks.Entry(1,side,5)),List.of(2L))).isTrue();
        assertThat(redis.opsForZSet().score(prefix+"{"+show+"}:"+middle.name(),"1")).isNull();
        assertThat(redis.opsForZSet().score(prefix+"{"+show+"}:"+middle.name(),"2")).isNull();
        assertThat(redis.opsForZSet().score(prefix+"{"+show+"}:"+side.name(),"1")).isEqualTo(5d);
        assertThat(redis.getExpire(versionKey)).isBetween(1L,30L);
        assertThat(ranks.patch(show,1,2,List.of(a,b),List.of())).isFalse();
        assertThat(ranks.version(show)).isEqualTo(3L);
        redis.delete(prefix+"{"+show+"}:SIDE_REAR");
        assertThat(ranks.patch(show,3,4,List.of(b),List.of())).isFalse();
        assertThat(ranks.version(show)).isNull();
        assertThat(redis.opsForValue().get(versionKey)).isEqualTo("3");
    }

    @Test void outOfOrderDeliveryCatchesUpLatestRevisionWithoutResurrectingCancelledWaits() {
        var first=fixture(1,1); register(first); long show=first.shows().getFirst(); projection().refresh(show);
        var second=another(first,1,false); register(second);
        tx(em->service(em,CLOCK).cancel(second.user(),second.group(),key()));
        var events=tx(em->em.createQuery("select e from BookingOutboxEvent e where e.showtimeId=:s order by e.aggregateVersion desc",BookingOutboxEvent.class)
                .setParameter("s",show).getResultList());
        var handler=new BookingWaitingOutboxHandler(dispatcher(CLOCK),projection(),false);
        for(var e:events) handler.handle(new BookingOutboxStore.Delivery(e.getId(),show,e.getAggregateVersion(),1,e.getEventType(),e.getGroupId(),e.getReason(),NOW,1,"test"));
        assertThat(ranks.version(show)).isEqualTo(events.getFirst().getAggregateVersion());
        assertThat(redis.opsForZSet().zCard(prefix+"{"+show+"}:MIDDLE_MIDDLE")).isEqualTo(2); // sentinel + first waiter
    }

    @Test void concurrentDeltaAndRebuildCannotOverwriteNewerRevision() throws Exception {
        long show=990004; var zone=SeatPosition.MIDDLE_MIDDLE;
        var old=new BookingWaitingRanks.Entry(1,zone,1); var latest=new BookingWaitingRanks.Entry(2,zone,2);
        ranks.replace(show,1,List.of(old));
        BookingPaymentTests.race(
                ()->ranks.patch(show,1,2,List.of(old),List.of()),
                ()->{ ranks.replace(show,3,List.of(latest)); return true; });
        assertThat(ranks.version(show)).isEqualTo(3L);
        assertThat(ranks.read(show,3,List.of(latest))).containsEntry(2L,0L);
        assertThat(redis.opsForZSet().score(prefix+"{"+show+"}:"+zone.name(),"1")).isNull();
    }

    @Test void repairDetectsEvictionEvenInAnUnrequestedZone() {
        var f=fixture(1,1); register(f); long show=f.shows().getFirst();
        projection().refresh(show);
        var zoneKey=prefix+"{"+show+"}:SIDE_REAR";
        redis.delete(zoneKey);
        readStatements.clear(); projection().repairIfNeeded(show);
        assertThat(readStatements).anyMatch(sql -> sql.contains("waiting_queues"));
        assertThat(redis.opsForZSet().score(zoneKey,"_")).isEqualTo(-1d);
        assertThat(cachedState(f,ranks).items()).isEqualTo(state(f).items());
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
        var broken=mock(BookingWaitingProjection.class); doThrow(new IllegalStateException("offline")).when(broken).update(anyLong());
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

    @Test void busyZoneSkipsDomainLocksAndDoesNotAcknowledgeEventUntilRetried() {
        tx(em -> { em.createQuery("delete from BookingOutboxEvent").executeUpdate(); return null; });
        var f=fixture(1,1);
        register(f,List.of(f.shows().getFirst()),key());
        var dispatcher=dispatcher(CLOCK);
        var gate=new BookingDispatchGate(redis,true,30000,db.jdbcUrl());
        ReflectionTestUtils.setField(dispatcher,"dispatchGate",gate);
        var store=new BookingOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),manager());
        var handler=new BookingWaitingOutboxHandler(dispatcher,projection(),true);
        var worker=new BookingOutboxWorker(store,List.of(handler),new AdminMaintenanceGate(),CLOCK);
        gate.run(f.shows().getFirst(),SeatPosition.MIDDLE_MIDDLE,() -> {
            readStatements.clear();
            assertThatThrownBy(() -> dispatcher.dispatch(f.shows().getFirst())).isInstanceOf(BookingDispatchGate.Busy.class);
            // Discovery reads identify active zones before checking their independent Redis leases.
            assertThat(readStatements).noneMatch(sql -> sql.contains("for update") || sql.startsWith("insert ") || sql.startsWith("update "));
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

    @Test void busyZoneDoesNotPreventDispatchingAnotherZoneOfTheSameShow() {
        var first = fixture(1, 2); var second = another(first, 1, false);
        tx(em -> { inventory(em, first.shows().getFirst()).getLast().getSeat().setSeatPosition(SeatPosition.SIDE_FRONT); return null; });
        register(first, List.of(first.shows().getFirst()), key());
        tx(em -> service(em, CLOCK).register(second.user(), second.group(), key(),
                new smartticketing.dto.booking.WaitingRequest(List.of(second.shows().getFirst()), SeatPosition.SIDE_FRONT)));
        var dispatcher = dispatcher(CLOCK);
        var gate = new BookingDispatchGate(redis, true, 30000, db.jdbcUrl());
        ReflectionTestUtils.setField(dispatcher, "dispatchGate", gate);
        gate.run(first.shows().getFirst(), SeatPosition.MIDDLE_MIDDLE, () -> {
            assertThatThrownBy(() -> dispatcher.dispatch(first.shows().getFirst())).isInstanceOf(BookingDispatchGate.Busy.class);
            statuses(first, QueueStatus.WAITING);
            statuses(second, QueueStatus.HOLDING);
            return 0;
        });
        assertThat(dispatcher.dispatch(first.shows().getFirst())).isEqualTo(1);
        statuses(first, QueueStatus.HOLDING);
    }
}
