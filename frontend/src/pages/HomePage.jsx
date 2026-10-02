import { Link } from 'react-router-dom';
import { useState } from 'react';
import useCatalog from '../booking/useCatalog.js';
import MovieHero from '../booking/MovieHero.jsx';
import { movieImage } from '../booking/images.js';
import HorizontalRail from '../components/HorizontalRail.jsx';
import { QueryStatus } from '../booking/BookingComponents.jsx';
import ui from '../booking/BookingComponents.module.css';
import styles from './HomePage.module.css';
import { formatRating } from '../booking/format.js';

function HomeMovieCard({ movie, isUpcoming }) {
    const [failed, setFailed] = useState(false);
    return <Link className={styles.movie} to={`/movies?movie=${movie.id}`}>
        {movie.posterUrl && !failed ? <img {...movieImage(movie.posterUrl, { sizes: '(max-width: 599px) 42vw, (max-width: 999px) 32vw, 230px' })} alt={`${movie.title} 포스터`} loading="lazy" decoding="async" onError={() => setFailed(true)} /> : <div className={styles.placeholder}>이미지 준비 중</div>}
        <strong>{movie.title}</strong>
        <small>{formatRating(movie.rating)} · {movie.runningTime ? `${movie.runningTime}분` : '시간 미확인'}</small>
        {/* 상영예정작이면 개봉일 표시, 상영작이면 누적관객수 및 예매율 표시 */}
        {isUpcoming ? (
            <small>{movie.releaseDate ? `${movie.releaseDate} 개봉` : '개봉일 미정'}</small>
        ) : (
            <small>누적 {(movie.audienceCount ?? 0).toLocaleString()} · 예매율 {movie.bookingRate ?? 0}%</small>
        )}
    </Link>;
}

function MovieSection({ title, movies, loaded, isUpcoming }) {
    return <section className={styles.catalog} aria-label={title}>
        <h2>{title}</h2>
        {loaded && !movies.length && <p role="status">표시할 영화가 없습니다.</p>}
        {movies.length > 0 && <HorizontalRail label={title} className={styles.rail}>
            {movies.map(movie => <HomeMovieCard key={movie.id} movie={movie} isUpcoming={isUpcoming} />)}
        </HorizontalRail>}
    </section>;
}

export default function HomePage() {
    const heroQuery = useCatalog('movies', { page: 0, size: 20, landscapeOnly: true });
    const featured = (heroQuery.data?.items || []).find(movie => movie.backdropUrl?.trim());

    const chart = useCatalog('main', {});
    const loaded = Boolean(chart.data);

    return <div className={ui.surface}>
        {featured && <MovieHero key={featured.id} movie={featured} home />}
        <QueryStatus query={chart} />
        <MovieSection title="상영작" movies={chart.data?.nowShowing || []} loaded={loaded} />
        <MovieSection title="상영예정작" movies={chart.data?.comingSoon || []} loaded={loaded} isUpcoming />
    </div>;
}