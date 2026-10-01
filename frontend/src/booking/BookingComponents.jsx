import GlassButton from '../components/GlassButton.jsx';
import ui from './BookingComponents.module.css';
import { dates } from './state.js';

export function QueryStatus({ query, empty = false }) {
    if (query.loading) return <p className={ui.feedback} role="status">불러오는 중…</p>;
    if (query.error) return <div className={ui.feedback} role="alert">{query.error.message} <GlassButton onClick={query.retry}>다시 시도</GlassButton></div>;
    if (empty) return <p className={ui.feedback} role="status">조건에 맞는 결과가 없습니다.</p>;
    return null;
}
export function DateCards({ value, today, onChange }) {
    return <div className={ui.dates} aria-label="관람 날짜">{dates(today).map((date, index) => <button key={date} aria-label={date}
        aria-pressed={value === date} onClick={() => onChange(date)}><span>{index === 0 ? '오늘' : index === 1 ? '내일' : new Intl.DateTimeFormat('ko-KR', { weekday: 'long', timeZone: 'Asia/Seoul' }).format(new Date(`${date}T12:00:00+09:00`))}</span><strong>{date.slice(8)}</strong><small>{Number(date.slice(5, 7))}월</small></button>)}</div>;
}
export function MovieCard({ movie, selected, onClick }) {
    return <button className={ui.movie} aria-pressed={selected} onClick={onClick}>
        <div className={ui.movieImage}>{movie.posterUrl ? <img src={movie.posterUrl} alt="" loading="lazy" /> : <div className={ui.poster}>POSTER</div>}{selected && <span className={ui.selectedBadge}>선택</span>}</div>
        <strong>{movie.title}</strong><small>{movie.rating || '등급 미확인'} · {movie.runningTime ? `${movie.runningTime}분` : '시간 미확인'}</small>
    </button>;
}
export function ShowtimeCard({ show, selected, label, onClick }) {
    const Tag = onClick ? 'button' : 'div';
    return <Tag className={ui.showtime} aria-pressed={onClick ? selected : undefined} onClick={onClick}>
        <small>{show.screenName}</small>
        <strong>{show.startTime.slice(11, 16)} → {show.endsNextDay && '익일 '}{show.endTime.slice(11, 16)}</strong>
        <small>{show.startTime.slice(0, 10)}</small>
        <span>{show.availableSeats} / {show.totalSeats}석 · {label}</span>
    </Tag>;
}
export function BookingButtons({ normal, disabled, normalDisabled, smartDisabled, onEnter }) {
    return <div className={ui.actions}>{normal && <GlassButton disabled={disabled || normalDisabled} onClick={() => onEnter('THEATER_NORMAL')}>일반예매</GlassButton>}
        <button className={ui.primary} disabled={disabled || smartDisabled} onClick={() => onEnter(normal ? 'THEATER_SMART' : 'MOVIE_SMART')}>스마트예매</button></div>;
}
export function Pagination({ data, page, onChange }) {
    if (!data || data.totalElements <= data.size) return null;
    return <nav className={ui.actions} aria-label="목록 페이지"><GlassButton disabled={page === 0} onClick={() => onChange(page - 1)}>이전</GlassButton><span>{page + 1} 페이지</span><GlassButton disabled={(page + 1) * data.size >= data.totalElements} onClick={() => onChange(page + 1)}>다음</GlassButton></nav>;
}
