package smartticketing.booking;

import org.junit.jupiter.api.Test;
import smartticketing.entity.*;
import smartticketing.entity.enums.*;
import smartticketing.service.ShowtimeInventoryService;
import smartticketing.service.ShowtimeScheduleSeedService;

import java.sql.Connection;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ShowtimePreparationConcurrencyTests {
    @Test void concurrentScheduleRunsReadExistingShowsAfterTheaterLock() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            Long theaterId;
            try (var em = database.open()) {
                em.getTransaction().begin();
                var theater = new Theater(); theater.setName("동시 편성"); theater.setAddress("서울");
                theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId("concurrent-schedule"); em.persist(theater);
                theaterId = theater.getId();
                var movie = new Movie(); movie.setTmdbMovieId(1L); movie.setTitle("영화"); movie.setRunningTime(120);
                movie.setReleaseDate(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1)); em.persist(movie);
                em.getTransaction().commit();
            }
            var ready = new CountDownLatch(2);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> seed = () -> {
                    try (var em = database.open()) {
                        em.getTransaction().begin(); ready.countDown();
                        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                        try {
                            int created = new ShowtimeScheduleSeedService(em, 1, 2, "").seedTheater(theaterId).createdShowtimes();
                            em.getTransaction().commit(); return created;
                        } catch (RuntimeException failure) {
                            if (em.getTransaction().isActive()) em.getTransaction().rollback();
                            throw failure;
                        }
                    }
                };
                var first = executor.submit(seed); var second = executor.submit(seed);
                var counts = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
                assertThat(counts).filteredOn(n -> n == 0).hasSize(1);
                try (var em = database.open()) {
                    assertThat(em.createQuery("select count(s) from Showtime s", Long.class).getSingleResult())
                            .isEqualTo(counts.stream().mapToLong(Integer::longValue).sum());
                    assertThat(em.createQuery("select count(s) from Screen s", Long.class).getSingleResult()).isEqualTo(1);
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test void concurrentDiscoveryRechecksUnderLockAndCreatesInventoryOnlyOnce() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            var clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneId.of("Asia/Seoul"));
            Long screenId;
            try (var em = database.open()) {
                em.getTransaction().begin();
                var theater = new Theater(); theater.setName("동시 준비"); theater.setAddress("서울");
                theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId("concurrent-preparation"); em.persist(theater);
                var screen = new Screen(); screen.setTheater(theater); screen.setName("1관");
                screen.setSeedKey("schedule-v1-1"); em.persist(screen); screenId = screen.getId();
                var movie = new Movie(); movie.setTmdbMovieId(1L); movie.setTitle("영화"); em.persist(movie);
                var show = new Showtime(); show.setScreen(screen); show.setMovie(movie);
                show.setStartTime(LocalDateTime.now(clock).plusHours(1)); show.setEndTime(show.getStartTime().plusHours(2));
                show.setTotalSeats(0); show.setAvailableSeats(0); show.setStatus(ShowtimeStatus.SCHEDULED);
                show.setCreatedAt(LocalDateTime.now(clock)); show.setUpdatedAt(LocalDateTime.now(clock)); em.persist(show);
                em.getTransaction().commit();
            }
            var discovered = new CountDownLatch(2);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> prepare = () -> {
                    try (var em = database.open()) {
                        em.unwrap(org.hibernate.Session.class).doWork(c -> c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED));
                        var service = new ShowtimeInventoryService(em, clock);
                        em.getTransaction().begin();
                        assertThat(service.pendingScreenIds()).containsExactly(screenId);
                        em.getTransaction().commit(); em.clear();
                        discovered.countDown();
                        assertThat(discovered.await(30, TimeUnit.SECONDS)).isTrue();
                        em.getTransaction().begin();
                        try {
                            int created = service.prepare(screenId).createdShowtimeSeats();
                            em.getTransaction().commit();
                            return created;
                        } catch (RuntimeException failure) {
                            if (em.getTransaction().isActive()) em.getTransaction().rollback();
                            throw failure;
                        }
                    }
                };
                var first = executor.submit(prepare); var second = executor.submit(prepare);
                assertThat(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(120, 0);
                try (var em = database.open()) {
                    assertThat(em.createQuery("select count(i) from ShowtimeSeat i", Long.class).getSingleResult()).isEqualTo(120);
                    assertThat(new ShowtimeInventoryService(em, clock).pendingScreenIds()).isEmpty();
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
