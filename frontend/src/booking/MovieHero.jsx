import InlineDetails from '../components/InlineDetails.jsx';
import GlassButton from '../components/GlassButton.jsx';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import styles from './MovieHero.module.css';
import { movieImage } from './images.js';

function trailerSource(movie) {
    try {
        const url = new URL(movie.media?.type === 'TRAILER' ? movie.media.url : movie.trailerUrl);
        const key = url.hostname === 'youtu.be' ? url.pathname.slice(1)
            : ['www.youtube.com', 'youtube.com'].includes(url.hostname) ? url.searchParams.get('v') : null;
        return /^[\w-]{11}$/.test(key ?? '') ? `https://www.youtube-nocookie.com/embed/${key}` : null;
    } catch { return null; }
}

function Trailer({ title, src, onClose }) {
    const dialog = useRef(null);
    useEffect(() => { dialog.current.showModal(); }, []);
    return <dialog ref={dialog} className={styles.dialog} aria-label={`${title} 예고편`} onCancel={onClose} onClose={onClose}>
        <GlassButton autoFocus onClick={onClose} aria-label="예고편 닫기">닫기 ×</GlassButton>
        <iframe title={`${title} 예고편`} src={src} allow="fullscreen" allowFullScreen />
    </dialog>;
}

// A single full-bleed image: no second framed copy above the booking controls.
export default function MovieHero({ movie, home = false }) {
    const [failed, setFailed] = useState([]);
    const [playing, setPlaying] = useState(false);
    const image = movie.backdropUrl && !failed.includes(movie.backdropUrl) ? movie.backdropUrl : null;
    const original = movieImage(image, { hero: true }).src;
    const source = failed.includes(original) ? image : original;
    const video = trailerSource(movie);
    const logo = home && movie.logoUrl && !failed.includes(movie.logoUrl);
    const href = `/movies?movie=${movie.id}`;
    const title = logo
        ? <img className={styles.logo} src={movie.logoUrl} alt={movie.title} onError={() => setFailed(previous => [...previous, movie.logoUrl])} />
        : movie.title;
    const artwork = image && <img src={source} alt={`${movie.title} ${image === movie.backdropUrl ? '배경' : '포스터'}`}
        fetchPriority="high" onLoad={event => {
            if (event.currentTarget.naturalWidth <= event.currentTarget.naturalHeight) setFailed(previous => [...previous, movie.backdropUrl]);
        }} onError={() => setFailed(previous => [...previous, source])} />;
    return <section className={`${styles.hero} ${home ? styles.home : styles.detail}`} aria-label={`${movie.title} 영화 소개`}>
        {home ? <Link className={styles.artwork} to={href} aria-label={`${movie.title} 이미지로 예매하기`}>{artwork}</Link>
            : <div className={styles.artwork}>{artwork}</div>}
        <div className={styles.shade} aria-hidden="true" />
        <div className={styles.inner}>
            <div className={styles.copy}>
                {!home && <Link className={styles.back} to="/">← 홈으로</Link>}
                <h1 className={logo ? styles.logoTitle : undefined}>{home
                    ? <Link className={styles.titleLink} to={href}>{title}</Link>
                    : title}</h1>
                {!home && <p className={styles.meta}><InlineDetails items={[movie.rating || '등급 미확인', movie.runningTime ? `${movie.runningTime}분` : '상영시간 미확인']} /></p>}
                {!home && <p className={styles.synopsis}>{movie.description || '등록된 줄거리가 없습니다.'}</p>}
                {!home && video && <GlassButton className={styles.play} onClick={() => setPlaying(true)} aria-label={`${movie.title} 예고편 재생`} title="예고편 보기">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7Z" /></svg>
                </GlassButton>}
                {home && <Link className={styles.primary} to={href}>
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true" focusable="false">
                        <g transform="rotate(-35 12 12)"><path d="M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4Z" /><path d="M15 6v3m0 2v2m0 2v3" /></g>
                    </svg>예매하기</Link>}
                {!home && !image && <p className={styles.notice}>등록된 이미지가 없습니다.</p>}
                {!home && !video && <p className={styles.notice}>{image ? '예고편 대신 영화 이미지로 안내합니다.' : '등록된 예고편이 없습니다.'}</p>}
            </div>
        </div>
        {playing && <Trailer title={movie.title} src={video} onClose={() => setPlaying(false)} />}
    </section>;
}
