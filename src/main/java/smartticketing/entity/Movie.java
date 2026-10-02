package smartticketing.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "movies")
@Data
@NoArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_movie_id", nullable = false, unique = true)
    private Long tmdbMovieId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "running_time")
    private Integer runningTime;

    @Column(length = 20)
    private String rating;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "poster_url", length = 500)
    private String posterUrl;

    @Column(name = "backdrop_url", length = 500)
    private String backdropUrl;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "trailer_url", length = 500)
    private String trailerUrl;

    // 영상이 없는 응답도 확인한 것으로 기록하여 매 시작마다 다시 요청하지 않는다.
    @Column(name = "metadata_fetched_at")
    private LocalDateTime metadataFetchedAt;

    // 이미지가 없는 응답도 확인 완료로 기록한다. 기존 행의 null은 최초 보충 대상이다.
    @Column(name = "image_metadata_fetched_at")
    private LocalDateTime imageMetadataFetchedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "audience_count", nullable = false)
    private long audienceCount = 0;

    @Column(length = 255)
    private String genres;

    @Column(length = 100)
    private String director;

    @Column(name = "cast_names", length = 500)
    private String castNames;
}
