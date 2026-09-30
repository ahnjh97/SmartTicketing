package smartticketing.booking;

import smartticketing.entity.*;
import smartticketing.entity.enums.TheaterBrand;
import smartticketing.service.BookingSeedService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BookingSeedConcurrencyTests {
    @Test void twoConcurrentSeedsCreateOnlyOneSetOfData() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            Long theaterId;
            try (var em = database.open()) {
                em.getTransaction().begin();
                var theater = new Theater(); theater.setName("동시성 테스트"); theater.setAddress("주소");
                theater.setBrand(TheaterBrand.CGV); theater.setKakaoPlaceId("test"); em.persist(theater);
                var movie = new Movie(); movie.setTmdbMovieId(1L); movie.setTitle("영화"); movie.setRunningTime(120); em.persist(movie);
                theaterId = theater.getId(); em.getTransaction().commit();
            }
            var clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> task = () -> {
                    start.await();
                    try (var em = database.open()) {
                        em.getTransaction().begin();
                        try {
                            int created = new BookingSeedService(em, clock).seed(List.of(theaterId)).createdShowtimes();
                            em.getTransaction().commit(); return created;
                        } catch (RuntimeException failure) {
                            if (em.getTransaction().isActive()) em.getTransaction().rollback();
                            throw failure;
                        }
                    }
                };
                var first = executor.submit(task); var second = executor.submit(task); start.countDown();
                assertThat(List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(21, 0);
                try (var em = database.open()) {
                    assertThat(em.createQuery("select count(s) from Showtime s", Long.class).getSingleResult()).isEqualTo(21);
                    assertThat(em.createQuery("select count(s) from ShowtimeSeat s", Long.class).getSingleResult()).isEqualTo(2268);
                }
            } finally {
                executor.shutdownNow();
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Seed workers did not terminate");
            }
        }
    }
}
