import { useSearchParams, Link } from 'react-router-dom';
import { useState } from 'react';
import useCatalog from '../booking/useCatalog.js';
import MovieHero from '../booking/MovieHero.jsx';
import { movieImage } from '../booking/images.js';
import HorizontalRail from '../components/HorizontalRail.jsx';
import { Pagination, QueryStatus } from '../booking/BookingComponents.jsx';
import ui from '../booking/BookingComponents.module.css';
import styles from './HomePage.module.css';

function HomeMovieCard({ movie }) {
    const [failed, setFailed] = useState(false);
    return <Link className={styles.movie} to={`/movies?movie=${movie.id}`}>
        {movie.posterUrl && !failed ? <img {...movieImage(movie.posterUrl, { sizes: '(max-width: 599px) 42vw, (max-width: 999px) 32vw, 230px' })} alt={`${movie.title} 포스터`} loading="lazy" decoding="async" onError={() => setFailed(true)} /> : <div className={styles.placeholder}>이미지 준비 중</div>}
        <strong>{movie.title}</strong><small>{movie.rating || '등급 미확인'} · {movie.runningTime ? `${movie.runningTime}분` : '시간 미확인'}</small>
    </Link>;
}

export default function HomePage() {
    const [params, setParams] = useSearchParams();
    const rawPage = params.get('page');
    const page = /^\d+$/.test(rawPage ?? '') ? Math.min(Number(rawPage), 2147483647) : 0;
    const query = useCatalog('movies', { page, size: 20, landscapeOnly: true });
    const movies = (query.data?.items || []).filter(movie => movie.backdropUrl?.trim());
    const featured = movies[0];
    return <div className={ui.surface}>
        {featured && <MovieHero key={featured.id} movie={featured} home />}
        <section className={styles.catalog} aria-label="영화 목록">
            <h2>영화 둘러보기 <span aria-hidden="true">›</span></h2>
            <QueryStatus query={query} />
            {query.data && !movies.length && <p role="status">가로 배경 이미지가 준비된 영화가 없습니다.</p>}
            <HorizontalRail key={page} label="영화 둘러보기" className={styles.rail}>{movies.map(movie => <HomeMovieCard key={`${movie.id}:${movie.posterUrl}`} movie={movie} />)}</HorizontalRail>
            <Pagination data={query.data} page={page} onChange={next => setParams({ page: String(next) })} />
        </section>
    </div>;
}
