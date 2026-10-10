package smartticketing.booking;

import smartticketing.entity.Movie;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BookingSchemaUpdateTests {
    @Test void notificationNavigationColumnsUpgradeWithoutRewritingLegacyData() throws Exception {
        try (var database = new TemporaryMysqlDatabase()) {
            long id;
            try (var em = database.open()) {
                em.getTransaction().begin();
                var user = new smartticketing.entity.Users(); user.setName("기존 회원"); user.setNickname("기존 회원"); em.persist(user);
                var n = new smartticketing.entity.Notification(); n.setUser(user); n.setType(smartticketing.entity.enums.NotificationType.PAYMENT_FAILED);
                n.setMessage("보존할 알림"); n.setRead(true); n.setCreatedAt(java.time.LocalDateTime.of(2026,1,1,12,0)); em.persist(n);
                em.getTransaction().commit(); id = n.getId();
                // Reproduce the notification schema before booking/reservation links in this owned disposable database.
                em.getTransaction().begin();
                em.createNativeQuery("alter table notifications drop column booking_group_id, drop column reservation_id").executeUpdate();
                em.getTransaction().commit();
            }
            database.restartPersistence();
            database.restartPersistence();
            try (var em = database.open()) {
                var old = em.find(smartticketing.entity.Notification.class, id);
                assertThat(old.getMessage()).isEqualTo("보존할 알림"); assertThat(old.isRead()).isTrue();
                assertThat(old.getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026,1,1,12,0));
                assertThat(old.getBookingGroupId()).isNull(); assertThat(old.getReservationId()).isNull();
            }
        }
    }
    @Test void hibernateAddsNullableMetadataWithoutChangingExistingMovie() throws Exception {
        try (var database = new TemporaryMysqlDatabase("""
                CREATE TABLE movies (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                  tmdb_movie_id BIGINT NOT NULL UNIQUE, title VARCHAR(255) NOT NULL,
                  description TEXT, running_time INT, rating VARCHAR(20), release_date DATE,
                  poster_url VARCHAR(500), trailer_url VARCHAR(500), is_active BIT NOT NULL)
                """, """
                INSERT INTO movies(tmdb_movie_id,title,running_time,is_active)
                VALUES (11,'기존 영화',125,0)
                """)) {
            try (var em = database.open()) {
                var movie = em.createQuery("from Movie where tmdbMovieId = 11", Movie.class).getSingleResult();
                assertThat(movie.getTitle()).isEqualTo("기존 영화");
                assertThat(movie.getRunningTime()).isEqualTo(125);
                assertThat(movie.isActive()).isFalse();
                assertThat(movie.getMetadataFetchedAt()).isNull();
                assertThat(movie.getImageMetadataFetchedAt()).isNull();
                assertThat(movie.getBackdropUrl()).isNull();
                assertThat(movie.getLogoUrl()).isNull();
                assertThat(em.createNativeQuery("select price_per_person from showtimes").getResultList()).isEmpty();
                assertThat(em.createNativeQuery("select seed_key from screens").getResultList()).isEmpty();
            }
        }
    }
}
