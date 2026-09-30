package smartticketing.dto.movie;

import smartticketing.entity.Movie;

/** 조회 API에서 재사용할 미디어 선택 결과. 영상이 없으면 포스터, 둘 다 없으면 NONE. */
public record MovieMedia(String type, String url, String posterUrl) {
    public static MovieMedia from(Movie movie) {
        if (movie.getTrailerUrl() != null && !movie.getTrailerUrl().isBlank()) {
            return new MovieMedia("TRAILER", movie.getTrailerUrl(), movie.getPosterUrl());
        }
        if (movie.getPosterUrl() != null && !movie.getPosterUrl().isBlank()) {
            return new MovieMedia("POSTER", movie.getPosterUrl(), movie.getPosterUrl());
        }
        return new MovieMedia("NONE", null, null);
    }
}
