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
                        BookingGroupService.class, BookingHoldService.class, BookingIdempotency.class, BookingExpiryWorker.class, CurrentUser.class)
                .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
                .withBean(OAuth2SuccessHandler.class, () -> mock(OAuth2SuccessHandler.class))
                .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class))
                .withBean("bookingQueryClock", Clock.class, () -> clock)
                .withBean(Validator.class, () -> validator)
                .withBean(EntityManager.class, () -> SharedEntityManagerCreator.createSharedEntityManager(db.factory()))
                .withBean(PlatformTransactionManager.class, () -> new JpaTransactionManager(db.factory()));
    }

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
                em.persist(s); var ss = new ShowtimeSeat(); ss.setShowtime(sh); ss.setSeat(s); em.persist(ss); ids[n+2] = s.getId();
            }
            em.getTransaction().commit(); return ids;
        }
    }
}
