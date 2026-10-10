package smartticketing.booking;

import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import smartticketing.entity.*;
import smartticketing.entity.BookingOutboxEvent.*;
import smartticketing.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static smartticketing.booking.BookingWaitingTests.*;

@org.junit.jupiter.api.Tag("core")
class BookingOutboxTests {
    static final List<String> statements=new java.util.concurrent.CopyOnWriteArrayList<>();
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(statements::add); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    @BeforeEach void clearEvents() {
        tx(em -> { em.createQuery("delete from BookingOutboxEvent").executeUpdate(); return null; });
    }
    BookingOutboxStore store() {
        var manager=new JpaTransactionManager(db.factory());
        manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
        return new BookingOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(db.factory()),manager);
    }
    List<BookingOutboxEvent> events() {
        return tx(em -> em.createQuery("select e from BookingOutboxEvent e order by e.id", BookingOutboxEvent.class).getResultList());
    }
    void append(long show) {
        tx(em -> { BookingOutbox.append(em,show,Type.WAITING_CHANGED,null,"TEST",NOW); return null; });
    }
    Optional<BookingOutboxStore.Delivery> claim(BookingOutboxStore store, LocalDateTime now) {
        return store.claim(EnumSet.allOf(Type.class),now,Duration.ofSeconds(30));
    }

    @Test void springProxiesAppendAndRollbackInTheExistingJpaTransaction() {
        var factory=org.mockito.Mockito.mock(jakarta.persistence.EntityManagerFactory.class,
                org.mockito.AdditionalAnswers.delegatesTo(db.factory()));
        var info=org.mockito.Mockito.mock(EntityManagerFactoryInfo.class);
        org.mockito.Mockito.doAnswer(invocation -> ExtendedEntityManagerCreator
                .createApplicationManagedEntityManager(db.open(),info)).when(factory).createEntityManager();
        var manager=new JpaTransactionManager(factory);
        manager.setJpaDialect(new org.springframework.orm.jpa.vendor.HibernateJpaDialect());
        var transaction=new TransactionTemplate(manager);
        var shared=SharedEntityManagerCreator.createSharedEntityManager(factory);
        long show=990001;
        transaction.executeWithoutResult(status -> {
            // Reproduce the production proxy's false negative with an active DB transaction.
            assertThat(shared.isJoinedToTransaction()).isFalse();
            assertThat(((jakarta.persistence.EntityManager)shared.getDelegate()).isJoinedToTransaction()).isTrue();
            BookingOutbox.append(shared,show,Type.BOOKING_CHANGED,null,"COMMIT",NOW);
        });
        assertThat(events()).singleElement().satisfies(e -> assertThat(e.getAggregateVersion()).isEqualTo(1));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            BookingOutbox.append(shared,show,Type.BOOKING_CHANGED,null,"ROLLBACK",NOW);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");
        transaction.executeWithoutResult(status -> BookingOutbox.append(shared,show,Type.BOOKING_CHANGED,null,"AFTER",NOW));
        assertThat(events()).extracting(BookingOutboxEvent::getAggregateVersion).containsExactly(1L,2L);
        assertThatThrownBy(() -> BookingOutbox.append(shared,show,Type.BOOKING_CHANGED,null,"NO_TX",NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events()).hasSize(2);
    }

    @Test void extendedProxyWithoutTransactionCannotAppend() {
        try(var raw=db.open()) {
            var proxy=ExtendedEntityManagerCreator.createApplicationManagedEntityManager(raw,
                    org.mockito.Mockito.mock(EntityManagerFactoryInfo.class));
            assertThatThrownBy(() -> BookingOutbox.append(proxy,990002L,Type.BOOKING_CHANGED,null,"NO_TX",NOW))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Outbox requires the domain transaction");
        }
        assertThat(events()).isEmpty();
    }

    @Test void registrationAndEventsCommitTogetherAndReplayDoesNotDuplicate() {
        var f=fixture(1,1); var key=key();
        assertThat(register(f,f.shows(),key).status()).isEqualTo(201);
        assertThat(events()).hasSize(2).allMatch(e -> e.getGroupId().equals(f.group()) && e.getAggregateVersion()==1);
        register(f,f.shows(),key);
        state(f);
        assertThat(events()).hasSize(2);
    }

    @Test void rollbackRemovesDomainChangeEventAndVersion() {
        var f=fixture(1,1);
        assertThatThrownBy(() -> tx(em -> {
            service(em,CLOCK).register(f.user(),f.group(),key(),new smartticketing.dto.booking.WaitingRequest(f.shows()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events()).isEmpty();
        assertThat(state(f).items()).isEmpty();
        register(f);
        assertThat(events()).allMatch(e -> e.getAggregateVersion()==1);
    }

    @Test void holdsAndExpiryRecordOtherShowPauseAndResume() {
        var f=fixture(1,1); register(f);
        assertThat(dispatcher(CLOCK).dispatch(f.shows().getFirst())).isEqualTo(1);
        assertThat(events()).anyMatch(e -> e.getShowtimeId().equals(f.shows().getLast()) && e.getReason().equals("WAITING_PAUSED"));
        var later=Clock.offset(CLOCK,Duration.ofMinutes(20));
        tx(em -> holds(em,later).expire(f.group()));
        assertThat(events()).anyMatch(e -> e.getReason().equals("HOLD_EXPIRED"));
        assertThat(events().stream().filter(e -> e.getShowtimeId().equals(f.shows().getLast())).toList())
                .extracting(BookingOutboxEvent::getReason).containsExactly("WAITING_WAITING","WAITING_PAUSED","WAITING_WAITING");
    }

    @Test void pendingAndLeasedEventsSurviveRestartAndStaleOwnerCannotAcknowledge() {
        append(90001); append(90002);
        var first=claim(store(),NOW).orElseThrow();
        db.restartPersistence();
        var store=store();
        var pending=claim(store,NOW).orElseThrow();
        assertThat(pending.id()).isNotEqualTo(first.id());
        assertThat(store.complete(pending,NOW)).isTrue();
        assertThat(claim(store,NOW.plusSeconds(29))).isEmpty();
        var recovered=claim(store,NOW.plusSeconds(30)).orElseThrow();
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(store.complete(first,NOW.plusSeconds(31))).isFalse();
        assertThat(store.retry(first,NOW.plusSeconds(31),new RuntimeException())).isFalse();
        assertThat(store.complete(recovered,NOW.plusSeconds(31))).isTrue();
    }

    @Test void failuresBackOffWithoutBlockingOtherEvents() {
        append(90001); append(90002);
        var store=store(); var failed=claim(store,NOW).orElseThrow();
        store.retry(failed,NOW,new IllegalArgumentException("secret must not be persisted"));
        var next=claim(store,NOW).orElseThrow();
        assertThat(next.id()).isNotEqualTo(failed.id()); store.complete(next,NOW);
        assertThat(claim(store,NOW.plusSeconds(1))).isEmpty();
        assertThat(claim(store,NOW.plusSeconds(2)).orElseThrow().id()).isEqualTo(failed.id());
        assertThat(events().getFirst().getLastError()).isEqualTo("IllegalArgumentException");
    }

    @Test void concurrentProducersHaveUniqueIncreasingVersions() throws Exception {
        long show=90003;
        long previous=tx(em -> em.createQuery("select s.revision from BookingOutboxStream s where s.showtimeId=:s",Long.class)
                .setParameter("s",show).getResultStream().findFirst().orElse(0L));
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> append(show)));
            for(var future:futures) future.get(20,TimeUnit.SECONDS);
        }
        assertThat(events()).extracting(BookingOutboxEvent::getAggregateVersion)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(previous+1,previous+8).boxed().toList());
    }

    @Test void concurrentWorkersClaimDistinctEvents() throws Exception {
        for(int i=0;i<8;i++) append(91000+i);
        var store=store();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<Long>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> claim(store,NOW).orElseThrow().id()));
            var ids=new HashSet<Long>();
            for(var future:futures) assertThat(ids.add(future.get(20,TimeUnit.SECONDS))).isTrue();
        }
        assertThat(claim(store,NOW)).isEmpty();
    }

    @Test void claimSkipsLockedRowInsteadOfWaitingForAnotherWorker() {
        append(90001); append(90002);
        var first=events().getFirst().getId();
        try(var em=db.open()) {
            em.getTransaction().begin();
            try {
                em.find(BookingOutboxEvent.class,first,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                assertThat(claim(store(),NOW).orElseThrow().id()).isNotEqualTo(first);
            } finally { em.getTransaction().rollback(); }
        }
    }

    @Test void paymentFailureConfirmationAndCancellationAreRecordedOncePerCommand() {
        var f=fixture(1,1); register(f); dispatcher(CLOCK).dispatch(f.shows().getFirst());
        long reservation=tx(em -> em.find(BookingGroupHold.class,f.group()).getReservation().getId());
        var failKey=key(); var payKey=key(); var cancelKey=key();
        for(int i=0;i<2;i++) tx(em -> BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),reservation,failKey,
                new smartticketing.dto.booking.MockPaymentRequest(smartticketing.entity.enums.PaymentMethod.MOCK,true)));
        for(int i=0;i<2;i++) tx(em -> BookingPaymentTests.service(em,CLOCK,true).pay(f.user(),reservation,payKey,
                new smartticketing.dto.booking.MockPaymentRequest(smartticketing.entity.enums.PaymentMethod.MOCK,false)));
        for(int i=0;i<2;i++) tx(em -> BookingPaymentTests.service(em,CLOCK,true).cancel(f.user(),reservation,cancelKey));
        assertThat(events().stream().filter(e -> e.getEventType()==Type.BOOKING_CHANGED).toList())
                .extracting(BookingOutboxEvent::getReason)
                .containsExactly("HOLD_ACQUIRED","PAYMENT_FAILED","PAYMENT_CONFIRMED","RESERVATION_CANCELLED");
    }

    @Test void globalAdminPurgeRecordsQueueOnlyShowsInSameTransaction() {
        var f=fixture(1,1); register(f);
        var source=new DriverManagerDataSource(db.jdbcUrl(),System.getenv("BOOKING_TEST_MYSQL_USER"),System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var admin=new AdminBookingService(source);
        var scope=new AdminBookingService.Scope(0,"global",null,"purge");
        var request=new AdminBookingService.Request(scope,admin.preview(scope).fingerprint(),"삭제");
        assertThatThrownBy(() -> transaction.execute(status -> { admin.execute(request); throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events()).hasSize(2);
        transaction.execute(status -> admin.execute(request));
        assertThat(events().stream().filter(e -> e.getReason().equals("ADMIN_BOOKING_PURGED")).map(BookingOutboxEvent::getShowtimeId).toList())
                .containsAll(f.shows());
    }

    @Test void workerLeavesUnhandledEventsPendingAndRetriesFailingHandlers() {
        append(90001);
        new BookingOutboxWorker(store(),List.of(),new AdminMaintenanceGate(),CLOCK).recover();
        assertThat(events().getFirst().getStatus()).isEqualTo(Status.PENDING);
        assertThat(events().getFirst().getAttempts()).isZero();
        BookingOutboxHandler handler=new BookingOutboxHandler() {
            public Set<Type> types() { return Set.of(Type.WAITING_CHANGED); }
            public void handle(BookingOutboxStore.Delivery event) { throw new IllegalStateException(); }
        };
        new BookingOutboxWorker(store(),List.of(handler),new AdminMaintenanceGate(),CLOCK).recover();
        assertThat(events().getFirst().getStatus()).isEqualTo(Status.PENDING);
        assertThat(events().getFirst().getAttempts()).isEqualTo(1);
        BookingOutboxHandler success=new BookingOutboxHandler() {
            public Set<Type> types() { return Set.of(Type.WAITING_CHANGED); }
            public void handle(BookingOutboxStore.Delivery event) { assertThat(event.schemaVersion()).isEqualTo(1); }
        };
        new BookingOutboxWorker(store(),List.of(success),new AdminMaintenanceGate(),Clock.offset(CLOCK,Duration.ofSeconds(2))).recover();
        assertThat(events().getFirst().getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test void adminDeletionKeepsTombstoneEventsAndRollsThemBackWithDeletion() {
        var f=fixture(1,1); register(f);
        var source=new DriverManagerDataSource(db.jdbcUrl(),System.getenv("BOOKING_TEST_MYSQL_USER"),System.getenv("BOOKING_TEST_MYSQL_PASSWORD"));
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var admin=new AdminDataService(source);
        var scope=new AdminDataService.Scope("showtimes","selected",List.of(f.shows().getFirst()),null,null,null,null);
        var request=new AdminDataService.DeleteRequest(scope,admin.preview(scope).fingerprint(),"삭제",true);
        assertThatThrownBy(() -> transaction.execute(status -> { admin.delete(request); throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        assertThat(events()).hasSize(2);
        transaction.execute(status -> admin.delete(request));
        Showtime deleted=tx(em -> em.find(Showtime.class,f.shows().getFirst()));
        assertThat(deleted).isNull();
        assertThat(events()).anyMatch(e -> e.getShowtimeId().equals(f.shows().getFirst()) && e.getReason().equals("ADMIN_CATALOG_CHANGED"));
        assertThat(events()).anyMatch(e -> e.getShowtimeId().equals(f.shows().getLast()) && e.getReason().equals("ADMIN_CATALOG_CHANGED"));
    }

    @Test void batchClaimUsesConstantStatementsAndBulkAck() {
        for(int i=0;i<32;i++) append(90001);
        statements.clear();
        var store=store();
        var batch=store.claimBatch(EnumSet.allOf(Type.class),NOW,Duration.ofSeconds(30),32);
        assertThat(batch).hasSize(32);
        assertThat(batch.stream().map(BookingOutboxStore.Delivery::leaseToken).distinct()).hasSize(1);
        assertThat(store.completeBatch(batch,NOW)).isEqualTo(32);
        assertThat(statements).hasSize(4); // expired range + ready range + bulk claim + bulk ack
        assertThat(events()).allMatch(e -> e.getStatus()==Status.COMPLETED);
    }

    @Test void concurrentBatchWorkersAndStaleBatchAcknowledgementsAreFenced() throws Exception {
        for(int i=0;i<24;i++) append(91000+i);
        var store=store();
        var batches=new ArrayList<List<BookingOutboxStore.Delivery>>();
        try(var pool=Executors.newFixedThreadPool(3)) {
            var futures=new ArrayList<Future<List<BookingOutboxStore.Delivery>>>();
            for(int i=0;i<3;i++) futures.add(pool.submit(() -> store.claimBatch(EnumSet.allOf(Type.class),NOW,Duration.ofSeconds(30),8)));
            for(var f:futures) batches.add(f.get(20,TimeUnit.SECONDS));
        }
        var all=batches.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(24);
        assertThat(all.stream().map(BookingOutboxStore.Delivery::id).distinct()).hasSize(24);
        var recovered=store.claimBatch(EnumSet.allOf(Type.class),NOW.plusSeconds(30),Duration.ofSeconds(30),24);
        assertThat(recovered).hasSize(24).allMatch(e -> e.attempt()==2);
        assertThat(store.completeBatch(all,NOW.plusSeconds(31))).isZero();
        assertThat(store.retryBatch(all,NOW.plusSeconds(31),new IllegalStateException())).isZero();
        assertThat(store.releaseBatch(all,NOW.plusSeconds(31))).isZero();
        assertThat(store.completeBatch(recovered,NOW.plusSeconds(31))).isEqualTo(24);
    }

    @Test void releaseAndRetryHaveDifferentAttemptAndAvailabilitySemantics() {
        for(int i=0;i<4;i++) append(92000+i);
        var store=store();
        var batch=store.claimBatch(EnumSet.allOf(Type.class),NOW,Duration.ofSeconds(30),4);
        assertThat(store.releaseBatch(batch.subList(0,2),NOW)).isEqualTo(2);
        assertThat(store.retryBatch(batch.subList(2,4),NOW,new IllegalArgumentException("sensitive"))).isEqualTo(2);
        var stats=store.backlog(NOW);
        assertThat(stats.pending()).isEqualTo(4);
        assertThat(stats.ready()).isEqualTo(2);
        var immediate=store.claimBatch(EnumSet.allOf(Type.class),NOW,Duration.ofSeconds(30),4);
        assertThat(immediate).hasSize(2).allMatch(e -> e.attempt()==1);
        var delayed=store.claimBatch(EnumSet.allOf(Type.class),NOW.plusSeconds(2),Duration.ofSeconds(30),4);
        assertThat(delayed).hasSize(2).allMatch(e -> e.attempt()==2);
        assertThat(events()).filteredOn(e -> e.getLastError()!=null).allMatch(e -> e.getLastError().equals("IllegalArgumentException"));
        assertThat(store.backlog(NOW.plusSeconds(32)).expired()).isEqualTo(4);
    }

    @Test void waitingHandlerCoalescesSameShowWithoutDroppingOtherHandlerEvents() {
        for(int i=0;i<8;i++) append(90001);
        var dispatcher=org.mockito.Mockito.mock(BookingWaitingDispatcher.class);
        var projection=org.mockito.Mockito.mock(BookingWaitingProjection.class);
        var delivered=new ArrayList<Long>();
        var each=new BookingOutboxHandler() {
            public Set<Type> types() { return EnumSet.allOf(Type.class); }
            public void handle(BookingOutboxStore.Delivery e) { delivered.add(e.id()); }
        };
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var worker=new BookingOutboxWorker(store(),List.of(new BookingWaitingOutboxHandler(dispatcher,projection,true),each),new AdminMaintenanceGate(),CLOCK,metrics);
        worker.recover();
        assertThat(delivered).hasSize(8);
        org.mockito.Mockito.verify(dispatcher,org.mockito.Mockito.times(1)).dispatch(90001L);
        org.mockito.Mockito.verify(projection,org.mockito.Mockito.times(2)).update(90001L);
        assertThat(metrics.get("booking.outbox.processed").counter().count()).isEqualTo(8);
        assertThat(metrics.get("booking.outbox.processing").timer().count()).isEqualTo(1);
        assertThat(metrics.get("booking.outbox.delay").timer().count()).isEqualTo(8);
    }

    @Test void budgetReleasesUnstartedBatchAndKeepsTheirAttemptsUnchanged() {
        for(int i=0;i<8;i++) append(90001+i);
        var handler=new BookingOutboxHandler() {
            public Set<Type> types() { return EnumSet.allOf(Type.class); }
            public void handle(BookingOutboxStore.Delivery e) {
                try { Thread.sleep(15); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
            }
        };
        var worker=new BookingOutboxWorker(store(),List.of(handler),new AdminMaintenanceGate(),CLOCK);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"runBudgetMs",1L);
        worker.recover();
        assertThat(events()).filteredOn(e -> e.getStatus()==Status.COMPLETED).hasSize(1);
        assertThat(events()).filteredOn(e -> e.getStatus()==Status.PENDING).hasSize(7).allMatch(e -> e.getAttempts()==0 && e.getLeaseToken()==null);
    }

    @Test void idlePollingBacksOffAndMaintenancePreventsClaimAndMetricsQueries() {
        var store=store(); var gate=new AdminMaintenanceGate();
        var handler=new BookingOutboxHandler() {
            public Set<Type> types() { return EnumSet.allOf(Type.class); }
            public void handle(BookingOutboxStore.Delivery e) {}
        };
        var worker=new BookingOutboxWorker(store,List.of(handler),gate,CLOCK);
        worker.recover();
        statements.clear();
        worker.poll();
        assertThat(statements).isEmpty();
        gate.maintain(() -> { worker.recover(); worker.refreshBacklogMetric(); });
        assertThat(statements).isEmpty();
    }

    @Test void failureOnOneShowDoesNotBlockAnotherAndUnacknowledgedWorkIsNotCounted() {
        append(90001); append(90002);
        var store=store();
        var handler=new BookingOutboxHandler() {
            public Set<Type> types() { return EnumSet.allOf(Type.class); }
            public void handle(BookingOutboxStore.Delivery e) {
                if(e.showtimeId()==90001L) throw new IllegalStateException();
                // Reproduce lease reclamation while this handler is still running.
                store.claimBatch(EnumSet.allOf(Type.class),NOW.plusSeconds(30),Duration.ofSeconds(30),8);
            }
        };
        var metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new BookingOutboxWorker(store,List.of(handler),new AdminMaintenanceGate(),CLOCK,metrics).recover();
        assertThat(metrics.get("booking.outbox.processed").counter().count()).isZero();
        assertThat(metrics.get("booking.outbox.retry").counter().count()).isEqualTo(1);
        assertThat(metrics.get("booking.outbox.stale.acks").counter().count()).isEqualTo(1);
    }

    @Test void unsupportedSchemaDoesNotPoisonValidEventsOfSameShow() {
        append(90001); append(90001);
        long bad=events().getFirst().getId();
        tx(em -> { em.createNativeQuery("update booking_outbox_events set schema_version=2 where id=:id").setParameter("id",bad).executeUpdate(); return null; });
        var dispatcher=org.mockito.Mockito.mock(BookingWaitingDispatcher.class);
        var projection=org.mockito.Mockito.mock(BookingWaitingProjection.class);
        new BookingOutboxWorker(store(),List.of(new BookingWaitingOutboxHandler(dispatcher,projection,true)),new AdminMaintenanceGate(),CLOCK).recover();
        assertThat(events()).filteredOn(e -> e.getId().equals(bad)).singleElement().satisfies(e -> assertThat(e.getStatus()).isEqualTo(Status.PENDING));
        assertThat(events()).filteredOn(e -> !e.getId().equals(bad)).singleElement().satisfies(e -> assertThat(e.getStatus()).isEqualTo(Status.COMPLETED));
        org.mockito.Mockito.verify(dispatcher).dispatch(90001L);
    }

    @Test void eventsAppendedWhileHandlingAreNeverAcknowledgedByTheOldBatch() {
        append(90001);
        var handler=new BookingOutboxHandler() {
            public Set<Type> types() { return EnumSet.allOf(Type.class); }
            public void handle(BookingOutboxStore.Delivery e) { append(90001); }
        };
        var worker=new BookingOutboxWorker(store(),List.of(handler),new AdminMaintenanceGate(),CLOCK);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"maxEvents",1);
        worker.recover();
        assertThat(events()).extracting(BookingOutboxEvent::getStatus).containsExactly(Status.COMPLETED,Status.PENDING);
    }
}
