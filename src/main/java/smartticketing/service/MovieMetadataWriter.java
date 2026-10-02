package smartticketing.service;

import smartticketing.entity.Movie;
import smartticketing.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 통신이 끝난 뒤에만 잠근다. 이미 저장된 값과 비활성 상태를 보존한다. */
@Service
public class MovieMetadataWriter {
    private final MovieRepository movies;

    public MovieMetadataWriter(MovieRepository movies) { this.movies = movies; }

    @Transactional
    public Movie saveMissing(Movie incoming) {
        var existing = movies.findForMetadataUpdate(incoming.getTmdbMovieId());
        if (existing.isEmpty()) return movies.saveAndFlush(incoming);
        var target = existing.get();
        if (blank(target.getTitle())) target.setTitle(incoming.getTitle());
        if (blank(target.getDescription())) target.setDescription(incoming.getDescription());
        if (target.getRunningTime() == null || target.getRunningTime() <= 0) target.setRunningTime(incoming.getRunningTime());
        if (blank(target.getRating())) target.setRating(incoming.getRating());
        if (target.getReleaseDate() == null) target.setReleaseDate(incoming.getReleaseDate());
        if (blank(target.getPosterUrl())) target.setPosterUrl(incoming.getPosterUrl());
        if (blank(target.getBackdropUrl())) target.setBackdropUrl(incoming.getBackdropUrl());
        if (blank(target.getLogoUrl())) target.setLogoUrl(incoming.getLogoUrl());
        if (blank(target.getTrailerUrl())) target.setTrailerUrl(incoming.getTrailerUrl());
        target.setMetadataFetchedAt(incoming.getMetadataFetchedAt());
        if (blank(target.getGenres())) target.setGenres(incoming.getGenres());
        if (blank(target.getDirector())) target.setDirector(incoming.getDirector());
        if (blank(target.getCastNames())) target.setCastNames(incoming.getCastNames());
        if (incoming.getImageMetadataFetchedAt() != null) {
            target.setImageMetadataFetchedAt(incoming.getImageMetadataFetchedAt());
        }
        return movies.saveAndFlush(target);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
