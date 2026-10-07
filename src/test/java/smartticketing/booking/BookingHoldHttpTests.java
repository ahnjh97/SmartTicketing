package smartticketing.booking;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import smartticketing.auth.*;
import smartticketing.config.SecurityConfig;
import smartticketing.controller.BookingHoldController;
import smartticketing.controller.BookingPaymentController;
import smartticketing.controller.BookingSmartController;
import smartticketing.controller.TicketController;
import smartticketing.repository.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.exception.ApiExceptionHandler;
import smartticketing.service.*;
import smartticketing.util.CurrentUser;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingHoldHttpTests {
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity @EnableWebMvc @EnableTransactionManagement
    static class WebConfig {}
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final JsonMapper json = JsonMapper.builder().build();

    private WebApplicationContextRunner runner(TemporaryMysqlDatabase db, Clock clock, Validator validator) {
        return new WebApplicationContextRunner()
                .withUserConfiguration(WebConfig.class, SecurityConfig.class, BookingHoldController.class, ApiExceptionHandler.class,
                        BookingGroupService.class, BookingHoldService.class, BookingIdempotency.class, BookingExpiryWorker.class, AdminMaintenanceGate.class, CurrentUser.class,
                        BookingPaymentController.class, BookingPaymentService.class, TicketController.class, TicketService.class, NotificationService.class,
                        BookingSmartController.class, BookingSmartService.class, BookingRecoveryService.class, BookingActivityService.class,
                        smartticketing.controller.BookingRecoveryController.class, smartticketing.controller.NotificationController.class,
                        smartticketing.controller.BookingWaitingController.class, BookingWaitingService.class, BookingWaitingDispatcher.class)
                .withPropertyValues("spring.profiles.active=test", "booking.mock-payment.allow-failure=true")
                .withBean(TicketRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(db.factory())).getRepository(TicketRepository.class))
                .withBean(ReservationRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(db.factory())).getRepository(ReservationRepository.class))
                .withBean(ReservationSeatRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(db.factory())).getRepository(ReservationSeatRepository.class))
                .withBean(NotificationRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(db.factory())).getRepository(NotificationRepository.class))
                .withBean(UsersRepository.class, () -> new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(db.factory())).getRepository(UsersRepository.class))
                .withBean(smartticketing.auth.AdminAccess.class, () -> mock(smartticketing.auth.AdminAccess.class))
            .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
                .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
                .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new JpaTransactionManager(db.factory())))
                .withBean("bookingQueryClock", Clock.class, () -> clock)
                .withBean(Validator.class, () -> validator)
                .withBean(EntityManager.class, () -> SharedEntityManagerCreator.createSharedEntityManager(db.factory()))
                .withBean(PlatformTransactionManager.class, () -> new JpaTransactionManager(db.factory()));
    }

    @org.junit.jupiter.api.Tag("core")
    @Test void authenticatedHttpFlowAndApplicationRestartRecoverFromDatabase() throws Exception {
        var statements = new ArrayList<String>();
        try (var db = new TemporaryMysqlDatabase(statements::add); var validation = Validation.buildDefaultValidatorFactory()) {
            long[] ids = seed(db);
            var reservationId = new AtomicLong(); var groupId = new AtomicLong();
            String key = UUID.randomUUID().toString();
            runner(db, clock, validation.getValidator()).run(context -> {
                assertThat(context).hasNotFailed();
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                mvc.perform(post("/api/booking-groups").contentType("application/json").content("{}"))
                        .andExpect(status().isUnauthorized());
                mvc.perform(get("/api/booking-groups/1")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/reservations/1")).andExpect(status().isUnauthorized());
                var auth = jwt().jwt(j -> j.subject(Long.toString(ids[0])));
                String request = """
                        {"entryPoint":"THEATER_NORMAL","movieId":%d,"viewingDate":"2026-10-01","partySize":2,
                        "selectedShowtimeId":%d,"audience":{"adultCount":1,"youthCount":1,
                        "companionsEligible":true,"guardianAccompanying":true}}
                        """.formatted(ids[1], ids[2]);
                mvc.perform(post("/api/booking-groups").with(auth).contentType("application/json").content(request))
                        .andExpect(status().isBadRequest());
                var created = mvc.perform(post("/api/booking-groups").with(auth).header("Idempotency-Key", key)
                        .contentType("application/json").content(request)).andExpect(status().isCreated())
                        .andExpect(jsonPath("$.audience.youthCount").value(1)).andReturn().getResponse().getContentAsString();
                groupId.set(json.readTree(created).get("id").asLong());
                String path = "/api/booking-groups/" + groupId.get() + "/manual-hold";
                String seats = "{\"seatIds\":[" + ids[3] + "," + ids[4] + "]}";
                statements.clear();
                var held = mvc.perform(post(path).with(auth).header("Idempotency-Key", key)
                        .contentType("application/json").content(seats)).andExpect(status().isCreated())
                        .andExpect(jsonPath("$.totalAmount").value(18000)).andExpect(jsonPath("$.expiresAt").value("2026-10-01T09:05:00+09:00"))
                        .andReturn().getResponse().getContentAsString();
                reservationId.set(json.readTree(held).get("id").asLong());
                mvc.perform(get("/api/booking-groups")).andExpect(status().isUnauthorized());
                mvc.perform(get("/api/booking-groups").with(auth)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].id").value(groupId.get())).andExpect(jsonPath("$.hasMore").value(false));
                mvc.perform(get("/api/booking-groups").with(jwt().jwt(j -> j.subject(Long.toString(ids[5])))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
                mvc.perform(get("/api/booking-groups/" + groupId.get() + "/recovery").with(jwt().jwt(j -> j.subject(Long.toString(ids[5])))))
                        .andExpect(status().isNotFound());
                mvc.perform(get("/api/notifications").with(auth)).andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].groupId").value(groupId.get())).andExpect(jsonPath("$[0].reservationId").value(reservationId.get()));
                var locks = statements.stream().filter(s -> s.contains("for update")).toList();
                assertThat(locks).anyMatch(s -> s.contains("booking_request_groups"))
                        .anyMatch(s -> s.contains("showtimes"))
                        .anyMatch(s -> s.contains("showtime_seats") && s.contains("order by"));
                int groupLock = lockIndex(locks, "booking_request_groups");
                int showLock = lockIndex(locks, "showtimes");
                int seatLock = lockIndex(locks, "showtime_seats");
                assertThat(groupLock).isLessThan(showLock); assertThat(showLock).isLessThan(seatLock);
                mvc.perform(post(path).with(auth).header("Idempotency-Key", key).contentType("application/json").content(seats))
                        .andExpect(status().isCreated()).andExpect(content().json(held));
                mvc.perform(get("/api/reservations/" + reservationId.get()).with(auth))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
                mvc.perform(get("/api/reservations/" + reservationId.get()).with(jwt().jwt(j -> j.subject(Long.toString(ids[5])))))
                        .andExpect(status().isNotFound());
                // 실제 Spring 트랜잭션 프록시 밖의 예외에서도 전체 변경이 롤백된다.
                var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                assertThatThrownBy(() -> tx.execute(status -> {
                    context.getBean(BookingGroupService.class).create(ids[0], UUID.randomUUID().toString(),
                            json.readValue(request, smartticketing.dto.booking.CreateBookingGroupRequest.class));
                    throw new IllegalStateException("rollback proxy");
                })).hasMessageContaining("rollback proxy");
                try (var em = db.open()) {
                    assertThat(em.createQuery("select count(g) from BookingRequestGroup g", Long.class).getSingleResult()).isEqualTo(1);
                }
            });
            db.restartPersistence();
            runner(db, Clock.offset(clock, Duration.ofMinutes(2)), validation.getValidator()).run(context -> {
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                var auth = jwt().jwt(j -> j.subject(Long.toString(ids[0])));
                mvc.perform(get("/api/booking-groups/" + groupId.get() + "/recovery").with(auth))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HOLDING"))
                        .andExpect(jsonPath("$.path").value(org.hamcrest.Matchers.containsString("reservation=" + reservationId.get())));
                mvc.perform(get("/api/reservations/" + reservationId.get()).with(auth))
                        .andExpect(jsonPath("$.expiresAt").value("2026-10-01T09:05:00+09:00"));
                mvc.perform(get("/api/notifications").with(auth)).andExpect(jsonPath("$.length()").value(1));
            });
            runner(db, Clock.offset(clock, Duration.ofMinutes(6)), validation.getValidator()).run(context -> {
                assertThat(context).hasNotFailed();
                context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                        context.getSourceApplicationContext(), Duration.ZERO));
                try (var em = db.open()) {
                    assertThat(em.find(Reservation.class, reservationId.get()).getStatus()).isEqualTo(ReservationStatus.EXPIRED);
                    assertThat(em.find(BookingGroupHold.class, groupId.get())).isNull();
                    assertThat(em.createQuery("select count(s) from ShowtimeSeat s where s.status=:status", Long.class)
                            .setParameter("status", SeatStatus.AVAILABLE).getSingleResult()).isEqualTo(2);
                }
            });
        }
    }

    private int lockIndex(List<String> locks, String table) {
        for (int n = 0; n < locks.size(); n++) if (locks.get(n).contains("from " + table + " ")) return n;
        throw new AssertionError("Missing lock: " + table);
    }

    @Test void paymentHttpOwnershipIdempotencyTicketCompatibilityAndCancellation() throws Exception {
        try (var db = new TemporaryMysqlDatabase(); var validation = Validation.buildDefaultValidatorFactory()) {
            var ids = seed(db);
            runner(db, clock, validation.getValidator()).run(context -> {
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                var auth = jwt().jwt(j -> j.subject(Long.toString(ids[0])));
                var other = jwt().jwt(j -> j.subject(Long.toString(ids[5])));
                var request = """
                    {"entryPoint":"THEATER_NORMAL","movieId":%d,"viewingDate":"2026-10-01","partySize":2,"selectedShowtimeId":%d,
                    "audience":{"adultCount":1,"youthCount":1,"companionsEligible":true,"guardianAccompanying":true}}
                    """.formatted(ids[1],ids[2]);
                var created = mvc.perform(post("/api/booking-groups").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                var group = json.readTree(created).get("id").asLong();
                var held = mvc.perform(post("/api/booking-groups/"+group+"/manual-hold").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content("{\"seatIds\":["+ids[3]+","+ids[4]+"]}"))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                long reservation = json.readTree(held).get("id").asLong();
                var path = "/api/reservations/"+reservation;
                mvc.perform(post(path+"/mock-payments").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
                mvc.perform(get(path+"/payment").with(other)).andExpect(status().isNotFound());
                mvc.perform(post(path+"/mock-payments").with(other).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content("{\"paymentMethod\":\"MOCK\"}")).andExpect(status().isNotFound());
                mvc.perform(post(path+"/cancel").with(other).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isNotFound());
                var key = UUID.randomUUID();
                var paid = mvc.perform(post(path+"/mock-payments").with(auth).header("Idempotency-Key",key).contentType("application/json")
                        .content("{\"paymentMethod\":\"MOCK\",\"amount\":1}"))
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(18000))
                        .andExpect(jsonPath("$.ticket.status").value("VALID")).andReturn().getResponse().getContentAsString();
                mvc.perform(post(path+"/mock-payments").with(auth).header("Idempotency-Key",key).contentType("application/json")
                        .content("{\"paymentMethod\":\"MOCK\"}")).andExpect(content().json(paid));
                long ticket = json.readTree(paid).get("ticket").get("ticketId").asLong();
                mvc.perform(get("/api/tickets/"+ticket).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.reservationId").value(reservation));
                mvc.perform(post("/api/tickets/reservations/"+reservation).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.ticketId").value(ticket));
                mvc.perform(post(path+"/cancel").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content("{\"seatIds\":[1]}")).andExpect(status().isBadRequest());
                mvc.perform(post(path+"/cancel").with(auth).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
                mvc.perform(get(path+"/payment").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
                mvc.perform(get("/api/tickets/"+ticket).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            });
        }
    }

    @Test void smartHttpReplaysAndUsesCommonPaymentWhileFailureNeverRegistersWaiting() throws Exception {
        try (var db = new TemporaryMysqlDatabase(); var validation = Validation.buildDefaultValidatorFactory()) {
            var ids = seed(db);
            runner(db, clock, validation.getValidator()).run(context -> {
                var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                var auth = jwt().jwt(j -> j.subject(Long.toString(ids[0])));
                String request = """
                        {"entryPoint":"THEATER_SMART","movieId":%d,"viewingDate":"2026-10-01","partySize":2,
                        "selectedShowtimeId":%d,"audience":{"adultCount":2,"youthCount":0,"companionsEligible":true,"guardianAccompanying":false}}
                        """.formatted(ids[1], ids[2]);
                var created=mvc.perform(post("/api/booking-groups").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                long group=json.readTree(created).get("id").asLong(); String path="/api/booking-groups/"+group+"/smart-hold";
                mvc.perform(post(path)).andExpect(status().isUnauthorized());
                mvc.perform(post(path).with(auth)).andExpect(status().isBadRequest());
                mvc.perform(post(path).with(auth).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content("{\"seatIds\":[1,2]}")).andExpect(status().isBadRequest());
                mvc.perform(post(path).with(jwt().jwt(j->j.subject(Long.toString(ids[5])))).header("Idempotency-Key",UUID.randomUUID()))
                        .andExpect(status().isNotFound());
                String key=UUID.randomUUID().toString();
                String held=mvc.perform(post(path).with(auth).header("Idempotency-Key",key)).andExpect(status().isCreated())
                        .andExpect(jsonPath("$.reservationType").value("SMART")).andExpect(jsonPath("$.totalAmount").value(20000))
                        .andReturn().getResponse().getContentAsString();
                mvc.perform(post(path).with(auth).header("Idempotency-Key",key).contentType("application/json").content("{}"))
                        .andExpect(status().isCreated()).andExpect(content().json(held));
                mvc.perform(post(path).with(auth).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("GROUP_UNAVAILABLE"));
                String second=mvc.perform(post("/api/booking-groups").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(request)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                long secondId=json.readTree(second).get("id").asLong();
                mvc.perform(post("/api/booking-groups/"+secondId+"/smart-hold").with(auth).header("Idempotency-Key",UUID.randomUUID()))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLD_OUT"));
                long reservation=json.readTree(held).get("id").asLong();
                mvc.perform(post("/api/reservations/"+reservation+"/mock-payments").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content("{\"paymentMethod\":\"MOCK\"}"))
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.ticket.status").value("VALID"));
                try (var em=db.open()) { assertThat(em.createQuery("select count(w) from WaitingQueue w",Long.class).getSingleResult()).isZero(); }
            });
        }
    }

    @Test void waitingHttpRequiresOwnerExplicitSelectionAndReplaysWithoutDuplicateRows() throws Exception {
        try (var db = new TemporaryMysqlDatabase(); var validation = Validation.buildDefaultValidatorFactory()) {
            long[] ids=seed(db);
            runner(db,clock,validation.getValidator()).run(context -> {
                assertThat(context).hasNotFailed();
                var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                var auth=jwt().jwt(j -> j.subject(Long.toString(ids[0])));
                var foreign=jwt().jwt(j -> j.subject(Long.toString(ids[5])));
                var body="""
                        {"entryPoint":"THEATER_SMART","movieId":%d,"viewingDate":"2026-10-01","partySize":2,
                        "selectedShowtimeId":%d,"audience":{"adultCount":2,"youthCount":0,"companionsEligible":true,"guardianAccompanying":false}}
                        """.formatted(ids[1],ids[2]);
                var created=mvc.perform(post("/api/booking-groups").with(auth).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                long group=json.readTree(created).get("id").asLong(); var path="/api/booking-groups/"+group+"/waiting-queues";
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).with(foreign)).andExpect(status().isNotFound());
                mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                        .andExpect(jsonPath("$.choices[0].showtimeId").value(ids[2]));
                mvc.perform(post(path).with(auth).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"showtimeIds\":[]}"))
                        .andExpect(status().isBadRequest());
                String key=UUID.randomUUID().toString(); String request="{\"showtimeIds\":["+ids[2]+"]}";
                mvc.perform(post(path).with(foreign).header("Idempotency-Key",key).contentType("application/json").content(request)).andExpect(status().isNotFound());
                var response=mvc.perform(post(path).with(auth).header("Idempotency-Key",key).contentType("application/json").content(request))
                        .andExpect(status().isCreated()).andExpect(jsonPath("$.items[0].queueNumber").value(1))
                        .andExpect(jsonPath("$.items[0].aheadCount").value(0)).andReturn().getResponse().getContentAsString();
                mvc.perform(post(path).with(auth).header("Idempotency-Key",key).contentType("application/json").content(request))
                        .andExpect(status().isCreated()).andExpect(content().json(response));
                var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                var em=context.getBean(EntityManager.class);
                tx.execute(s -> { em.find(Users.class,ids[0]).setBirthDate(null); return null; });
                // A skipped condition failure through the real Spring proxy must not poison the transaction.
                assertThat(context.getBean(BookingWaitingDispatcher.class).dispatch(ids[2])).isZero();
                tx.execute(s -> { em.find(Users.class,ids[0]).setBirthDate(LocalDate.of(1990,1,1)); return null; });
                context.getBean(BookingWaitingDispatcher.class).dispatch(ids[2]);
                mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("HOLDING"));
                mvc.perform(post("/api/booking-groups/"+group+"/cancel").with(foreign).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isNotFound());
                mvc.perform(post("/api/booking-groups/"+group+"/cancel").with(auth).header("Idempotency-Key",UUID.randomUUID()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.groupStatus").value("CANCELLED"))
                        .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
            });
        }
    }

    private long[] seed(TemporaryMysqlDatabase db) {
        try (var em = db.open()) {
            em.getTransaction().begin(); var now = LocalDateTime.now(clock);
            var u = new Users(); u.setName("관객"); u.setNickname("관객"); u.setBirthDate(LocalDate.of(1990, 1, 1)); em.persist(u);
            var other = new Users(); other.setName("타인"); other.setNickname("타인"); em.persist(other);
            var m = new Movie(); m.setTmdbMovieId(1L); m.setTitle("검증"); m.setRating("15"); em.persist(m);
            var t = new Theater(); t.setName("극장"); t.setAddress("주소"); t.setKakaoPlaceId("test"); t.setBrand(TheaterBrand.CGV); em.persist(t);
            var c = new Screen(); c.setName("관"); c.setTheater(t); em.persist(c);
            var sh = new Showtime(); sh.setMovie(m); sh.setScreen(c); sh.setStartTime(now.plusHours(5)); sh.setEndTime(now.plusHours(7));
            sh.setTotalSeats(2); sh.setAvailableSeats(2); sh.setPricePerPerson(10000); sh.setCreatedAt(now); sh.setUpdatedAt(now); em.persist(sh);
            long[] ids = {u.getId(), m.getId(), sh.getId(), 0, 0, other.getId()};
            for (int n=1; n<=2; n++) {
                var s = new Seat(); s.setScreen(c); s.setSeatRow("A"); s.setSeatNumber(n); s.setSeatPosition(SeatPosition.MIDDLE_MIDDLE);
                s.setAdjacencySegment("center"); s.setPositionInSegment(n);
                em.persist(s); var ss = new ShowtimeSeat(); ss.setShowtime(sh); ss.setSeat(s); em.persist(ss); ids[n+2] = s.getId();
            }
            em.getTransaction().commit(); return ids;
        }
    }
}
