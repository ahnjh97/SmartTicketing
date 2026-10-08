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

class BookingOutboxTests {
    @BeforeAll static void start() throws Exception { db = new TemporaryMysqlDatabase(); }
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
}
