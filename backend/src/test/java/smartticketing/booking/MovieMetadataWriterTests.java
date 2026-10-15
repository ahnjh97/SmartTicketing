package smartticketing.booking;

import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;
import smartticketing.service.MovieMetadataWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class MovieMetadataWriterTests {
    private static TemporaryMysqlDatabase database;
    private EntityManager em;
    private MovieMetadataWriter writer;
    @BeforeAll static void database() throws Exception { database = new TemporaryMysqlDatabase(); }
    @AfterAll static void cleanup() throws Exception { if (database != null) database.close(); }
    @BeforeEach void setup() {
        em = database.open(); em.getTransaction().begin();
        writer = new MovieMetadataWriter(new JpaRepositoryFactory(em).getRepository(MovieRepository.class));
    }
    @AfterEach void rollback() { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }

    @Test void enrichesOnlyMissingValuesAndPreservesInactiveMovie() {
        var old = new Movie(); old.setTmdbMovieId(11L); old.setTitle("수정한 제목"); old.setDescription("수정한 설명");
        old.setRunningTime(125); old.setPosterUrl("기존 포스터"); old.setActive(false); em.persist(old);
        var fetched = new Movie(); fetched.setTmdbMovieId(11L); fetched.setTitle("외부 제목"); fetched.setRunningTime(120);
        fetched.setRating("12"); fetched.setTrailerUrl("https://www.youtube.com/watch?v=aaaaaaaaaaa");
        fetched.setBackdropUrl("https://images.test/wide.jpg");
        fetched.setLogoUrl("https://images.test/logo.png");
        fetched.setMetadataFetchedAt(LocalDateTime.now());
        fetched.setImageMetadataFetchedAt(fetched.getMetadataFetchedAt());
        var saved = writer.saveMissing(fetched); Long id = saved.getId(); em.clear();
        var result = em.find(Movie.class, id);
        assertThat(result.getTitle()).isEqualTo("수정한 제목"); assertThat(result.getDescription()).isEqualTo("수정한 설명");
        assertThat(result.getRunningTime()).isEqualTo(125); assertThat(result.isActive()).isFalse();
        assertThat(result.getPosterUrl()).isEqualTo("기존 포스터"); assertThat(result.getRating()).isEqualTo("12");
        assertThat(result.getTrailerUrl()).isEqualTo(fetched.getTrailerUrl()); assertThat(result.getMetadataFetchedAt()).isNotNull();
        assertThat(result.getBackdropUrl()).isEqualTo(fetched.getBackdropUrl());
        assertThat(result.getLogoUrl()).isEqualTo(fetched.getLogoUrl());
        assertThat(result.getImageMetadataFetchedAt()).isNotNull();
    }

    @Test void repeatedMetadataWriteDoesNotDuplicateMovieOrClearGoodFields() {
        var first = new Movie(); first.setTmdbMovieId(11L); first.setTitle("영화"); first.setRunningTime(120);
        first.setTrailerUrl("기존 영상"); first.setMetadataFetchedAt(LocalDateTime.now()); writer.saveMissing(first);
        first.setBackdropUrl("기존 가로 이미지");
        first.setLogoUrl("기존 로고");
        var second = new Movie(); second.setTmdbMovieId(11L); second.setTitle("다른 제목"); second.setMetadataFetchedAt(LocalDateTime.now());
        second.setBackdropUrl("외부 가로 이미지");
        second.setLogoUrl("외부 로고");
        var saved = writer.saveMissing(second);
        assertThat(saved.getTrailerUrl()).isEqualTo("기존 영상"); assertThat(saved.getRunningTime()).isEqualTo(120);
        assertThat(saved.getBackdropUrl()).isEqualTo("기존 가로 이미지");
        assertThat(saved.getLogoUrl()).isEqualTo("기존 로고");
        assertThat(em.createQuery("select count(m) from Movie m", Long.class).getSingleResult()).isEqualTo(1);
    }
}
