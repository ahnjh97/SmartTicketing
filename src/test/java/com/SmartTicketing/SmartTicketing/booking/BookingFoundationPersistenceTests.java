package com.SmartTicketing.SmartTicketing.booking;

import com.SmartTicketing.SmartTicketing.entity.*;
import com.SmartTicketing.SmartTicketing.entity.enums.*;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 로컬 MySQL에 매 실행마다 독립 DB를 만들고 종료 시 제거한다. 개발 DB에는 접속하지 않는다. */
class BookingFoundationPersistenceTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 12, 0);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String MYSQL_SERVER = "jdbc:mysql://127.0.0.1:3306/";
    private static final String DATABASE_NAME = "booking_test_" + UUID.randomUUID().toString().replace("-", "");
    private static Connection adminConnection;
    private static boolean databaseCreated;
    private static SessionFactory factory;
    private EntityManager em;

    @BeforeAll
    static void createSchema() throws ClassNotFoundException, SQLException {
        String username = System.getenv("BOOKING_TEST_MYSQL_USER");
        String password = System.getenv("BOOKING_TEST_MYSQL_PASSWORD");
        if (username == null || username.isBlank() || password == null) {
            throw new IllegalStateException("Set BOOKING_TEST_MYSQL_USER and BOOKING_TEST_MYSQL_PASSWORD for local MySQL tests");
        }
        // URL/DB 이름을 외부 입력으로 받지 않는다. 생성에 성공한 임시 DB만 정리한다.
        adminConnection = DriverManager.getConnection(MYSQL_SERVER, username, password);
        try (var statement = adminConnection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + DATABASE_NAME + " CHARACTER SET utf8mb4");
            databaseCreated = true;
        }
        var config = new Configuration()
                .setProperty("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                .setProperty("hibernate.connection.url", MYSQL_SERVER + DATABASE_NAME)
                .setProperty("hibernate.connection.username", username)
                .setProperty("hibernate.connection.password", password)
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
                .setProperty("hibernate.show_sql", "false");
        config.setPhysicalNamingStrategy(new org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl());
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var bean : scanner.findCandidateComponents("com.SmartTicketing.SmartTicketing.entity")) {
            config.addAnnotatedClass(Class.forName(bean.getBeanClassName()));
        }
        factory = config.buildSessionFactory();
    }

    @AfterAll
    static void closeSchema() throws SQLException {
        try {
            if (factory != null) factory.close();
        } finally {
            if (adminConnection != null) {
                try (var connection = adminConnection; var statement = connection.createStatement()) {
                    if (databaseCreated) statement.executeUpdate("DROP DATABASE " + DATABASE_NAME);
                }
            }
        }
    }

    @BeforeEach
    void begin() {
        em = factory.createEntityManager();
        em.getTransaction().begin();
    }

    @AfterEach
    void rollback() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.close();
    }

    @Test
    void snapshotsKeepPriorityAndDuplicateSeatPreferencesAfterReload() {
        var user = user();
        var movie = movie();
        var first = theater();
        var second = theater();
        var group = group(user, movie);
        group.setSeatPreferences(new ArrayList<>(List.of(
                SeatPosition.MIDDLE_MIDDLE, SeatPosition.MIDDLE_MIDDLE, SeatPosition.SIDE_REAR)));
        group.setTheaterPreferences(new ArrayList<>(List.of(second, first)));
        var preferred = new UserPreferredSeat();
        preferred.setUser(user);
        preferred.setPriority(1);
        preferred.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);
        em.persist(preferred);
        em.flush();
        preferred.setSeatPosition(SeatPosition.SIDE_FRONT);
        em.flush();
        Long groupId = group.getId();
        List<Long> expectedTheaters = List.of(second.getId(), first.getId());
        em.clear();

        var loaded = em.find(BookingRequestGroup.class, groupId);
        assertThat(loaded.getPartySize()).isEqualTo(2);
        assertThat(loaded.getSeatPreferences()).containsExactly(
                SeatPosition.MIDDLE_MIDDLE, SeatPosition.MIDDLE_MIDDLE, SeatPosition.SIDE_REAR);
        assertThat(loaded.getTheaterPreferences().stream().map(Theater::getId).toList())
                .isEqualTo(expectedTheaters);
    }

    @Test
    void legacyReservationsAndMultipleLegacyQueueRowsDoNotRequireFabricatedGroups() {
        var user = user();
        var showtime = showtime(movie());
        var reservation = reservation(user, showtime, null);
        var first = waiting(user, showtime, null, 1);
        var second = waiting(user, showtime, null, 2);
        em.flush();
        em.clear();
        assertThat(em.find(Reservation.class, reservation.getId()).getRequestGroup()).isNull();
        assertThat(em.find(WaitingQueue.class, first.getId()).getRequestGroup()).isNull();
        assertThat(em.find(WaitingQueue.class, second.getId()).getRequestGroup()).isNull();
        assertThat(em.find(Movie.class, showtime.getMovie().getId()).getTrailerUrl()).isNull();
    }

    @Test
    void aGroupCannotRegisterTwiceForTheSameShowtime() {
        var user = user();
        var movie = movie();
        var group = group(user, movie);
        var showtime = showtime(movie);
        waiting(user, showtime, group, 1);
        em.flush();
        assertThatThrownBy(() -> {
            waiting(user, showtime, group, 2);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void groupCanWaitForMultipleShowtimesAndPausedStateKeepsItsNumber() {
        var user = user();
        var movie = movie();
        var group = group(user, movie);
        var first = waiting(user, showtime(movie), group, 4);
        var second = waiting(user, showtime(movie), group, 9);
        first.setStatus(QueueStatus.PAUSED);
        em.flush();
        Long id = first.getId();
        em.clear();
        var loaded = em.find(WaitingQueue.class, id);
        assertThat(loaded.getStatus()).isEqualTo(QueueStatus.PAUSED);
        assertThat(loaded.getQueueNumber()).isEqualTo(4);
        assertThat(em.find(WaitingQueue.class, second.getId()).getStatus()).isEqualTo(QueueStatus.WAITING);
    }

    @Test
    void databaseRejectsTwoActiveSlotsForTheSameGroup() {
        var user = user();
        var movie = movie();
        var group = group(user, movie);
        var first = reservation(user, showtime(movie), group);
        var second = reservation(user, showtime(movie), group);
        hold(group, first);
        em.flush();
        // save/merge가 기존 행을 갱신하는 경로와 구분하여 DB 중복 INSERT 제약을 검증한다.
        assertThatThrownBy(() -> em.createNativeQuery(
                "insert into booking_group_holds (group_id,reservation_id,expires_at) values (:g,:r,:e)")
                .setParameter("g", group.getId()).setParameter("r", second.getId())
                .setParameter("e", NOW.plusMinutes(5)).executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void reservationCannotOccupySlotsInTwoGroups() {
        var user = user();
        var movie = movie();
        var first = group(user, movie);
        var second = group(user, movie);
        var reservation = reservation(user, showtime(movie), first);
        hold(first, reservation);
        em.flush();
        assertThatThrownBy(() -> {
            hold(second, reservation);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void operationIdentityIsUniquePerUserAndOperation() {
        var user = user();
        operation(user, BookingOperationType.SMART_HOLD);
        operation(user, BookingOperationType.MANUAL_HOLD);
        operation(user(), BookingOperationType.SMART_HOLD);
        em.flush();
        assertThatThrownBy(() -> {
            operation(user, BookingOperationType.SMART_HOLD);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void addedCatalogAndAdjacencyMetadataRoundTripWithoutChangingSeatIdentity() {
        var movie = movie();
        movie.setTrailerUrl("https://www.youtube.com/watch?v=test");
        var sh = showtime(movie);
        var theater = sh.getScreen().getTheater();
        theater.setLatitude(new java.math.BigDecimal("37.5000000"));
        theater.setLongitude(new java.math.BigDecimal("127.0000000"));
        var seat = new Seat();
        seat.setScreen(sh.getScreen());
        seat.setSeatRow("A");
        seat.setSeatNumber(7);
        seat.setSeatPosition(SeatPosition.MIDDLE_FRONT);
        seat.setAdjacencySegment("middle");
        seat.setPositionInSegment(1);
        em.persist(seat);
        em.flush();
        em.clear();
        var loaded = em.find(Seat.class, seat.getId());
        assertThat(loaded.getSeatNumber()).isEqualTo(7);
        assertThat(loaded.getAdjacencySegment()).isEqualTo("middle");
        assertThat(loaded.getPositionInSegment()).isEqualTo(1);
        assertThat(em.find(Theater.class, theater.getId()).getLatitude())
                .isEqualByComparingTo("37.5");
        assertThat(em.find(Movie.class, movie.getId()).getTrailerUrl()).endsWith("v=test");
    }

    private Users user() {
        var value = new Users();
        value.setName("테스트");
        value.setNickname("user-" + SEQUENCE.incrementAndGet());
        em.persist(value);
        return value;
    }

    private Movie movie() {
        var value = new Movie();
        value.setTmdbMovieId((long) SEQUENCE.incrementAndGet());
        value.setTitle("테스트 영화");
        value.setRunningTime(120);
        em.persist(value);
        return value;
    }

    private Theater theater() {
        var value = new Theater();
        value.setName("테스트 극장");
        value.setAddress("테스트 주소");
        value.setBrand(TheaterBrand.CGV);
        value.setKakaoPlaceId("test-" + SEQUENCE.incrementAndGet());
        em.persist(value);
        return value;
    }

    private Showtime showtime(Movie movie) {
        var screen = new Screen();
        screen.setTheater(theater());
        screen.setName("1관");
        em.persist(screen);
        var value = new Showtime();
        value.setScreen(screen);
        value.setMovie(movie);
        value.setStartTime(NOW.plusDays(1));
        value.setEndTime(NOW.plusDays(1).plusHours(2));
        value.setTotalSeats(10);
        value.setAvailableSeats(10);
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        em.persist(value);
        return value;
    }

    private BookingRequestGroup group(Users user, Movie movie) {
        var value = new BookingRequestGroup();
        value.setUser(user);
        value.setMovie(movie);
        value.setEntryPoint(BookingEntryPoint.MOVIE_SMART);
        value.setViewingDate(LocalDate.of(2026, 10, 1));
        value.setStartTimeFrom(LocalTime.of(12, 0));
        value.setStartTimeTo(LocalTime.of(20, 0));
        value.setPartySize(2);
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        em.persist(value);
        return value;
    }

    private Reservation reservation(Users user, Showtime showtime, BookingRequestGroup group) {
        var value = new Reservation();
        value.setUser(user);
        value.setShowtime(showtime);
        value.setRequestGroup(group);
        value.setReservationType(ReservationType.SMART);
        value.setTotalAmount(20000);
        value.setExpiresAt(NOW.plusMinutes(5));
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        em.persist(value);
        return value;
    }

    private WaitingQueue waiting(Users user, Showtime showtime, BookingRequestGroup group, int number) {
        var value = new WaitingQueue();
        value.setUser(user);
        value.setShowtime(showtime);
        value.setRequestGroup(group);
        value.setQueueNumber(number);
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        em.persist(value);
        return value;
    }

    private void hold(BookingRequestGroup group, Reservation reservation) {
        var value = new BookingGroupHold();
        value.setRequestGroup(group);
        value.setReservation(reservation);
        value.setExpiresAt(NOW.plusMinutes(5));
        em.persist(value);
    }

    private void operation(Users user, BookingOperationType type) {
        var value = new BookingOperation();
        value.setUser(user);
        value.setOperationType(type);
        value.setRequestKey("request-key-000001");
        value.setRequestHash("a".repeat(64));
        value.setCreatedAt(NOW);
        value.setUpdatedAt(NOW);
        em.persist(value);
    }
}
