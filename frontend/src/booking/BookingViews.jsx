import AudiencePicker from './AudiencePicker.jsx';
import InlineDetails from '../components/InlineDetails.jsx';

import styles from './BookingViews.module.css';
import ui from './BookingComponents.module.css';
import useCatalog from './useCatalog.js';
import { formatRating } from './format.js';
import { Link } from 'react-router-dom';
import { DateCards, MovieCard, Pagination, QueryStatus } from './BookingComponents.jsx';
import BookingMapDialog from './BookingMapDialog.jsx';
import BookingMethodDialog from './BookingMethodDialog.jsx';
import TimeRangeMenu from './TimeRangeMenu.jsx';
import HorizontalRail from '../components/HorizontalRail.jsx';

function BookingDates({ booking }) {
 const { date, today, update, theaterMode } = booking;
 return <DateCards value={date} today={today} onChange={value => update({ date: value, ...(theaterMode ? { movie: null } : {}), showtime: null })} />;
}

function TheaterMovieRow({ movie, booking }) {
    const { theaterId, date, now, params, update } = booking;
    const shows = useCatalog('showtimes', { movieId: movie.movieId, theaterId, date });
    const items = (shows.data?.items || []).filter(show => Date.parse(show.startTime) > now);
    return <li className={styles.movieRow}>
        <div className={styles.rowPoster}>{movie.posterUrl ? <img src={movie.posterUrl} alt="" loading="lazy" /> : <div className={ui.poster}>POSTER</div>}</div>
        <div className={styles.rowBody}>
            <div className={styles.rowTitle}>
                <strong>{movie.title}</strong>
                <small><InlineDetails items={[formatRating(movie.rating), movie.runningTime ? `${movie.runningTime}분` : '시간 미확인']} /></small>
            </div>
            <QueryStatus query={shows} empty={Boolean(shows.data && !items.length)} />
            <HorizontalRail label={`${movie.title} 상영 시간`} className={styles.rowShowtimes}>{items.map(show => {
                return <div key={show.id} className={styles.slot}>
                    <button className={ui.showtime} aria-haspopup="dialog" onClick={() => update({ movie: movie.movieId, showtime: show.id })}>
                        <small>{show.screenName}</small>
                        <strong><span>{show.startTime.slice(11, 16)}</span>{' '}<span className={styles.endTime}>→ {show.endTime.slice(11, 16)}</span></strong>
                        <span>{show.availableSeats} / {show.totalSeats}석 {!show.availableSeats ? '매진' : !show.layoutComplete ? '배치 미확인' : ''}</span>
                    </button>
                </div>;
            })}</HorizontalRail>
            {params.get('movie') === String(movie.movieId) && items.some(show => String(show.id) === params.get('showtime')) &&
                <BookingMethodDialog booking={booking} movie={movie} show={items.find(show => String(show.id) === params.get('showtime'))} />}

        </div>
    </li>;
}

export function TheaterBooking({ booking }) {
    const { user, authLoading, params, movieId, theaterId, dateValid, update, selectTheater, brand, selectBrand, list, theater, movies, selectedMovieExists, preferences, selectedShow } = booking;
    const brands = { FAVORITES: '선호극장', CGV: 'CGV', LOTTE_CINEMA: '롯데시네마', MEGABOX: '메가박스' };
    const chart = useCatalog(theaterId ? 'main' : null, {});
    const rank = new Map((chart.data?.nowShowing || []).map((m, index) => [m.id, index]));
    // Publish the list only after its ranking arrives, so rows never jump from ID order.
    const sortedMovies = [...(chart.data ? movies.data?.items || [] : [])]
        .sort((a, b) => (rank.get(a.movieId) ?? 999) - (rank.get(b.movieId) ?? 999));
    return (<>
        <section className={styles.theaterSelector} aria-label="극장 선택">
            <div className={styles.selectorHeading}><h2>극장 선택</h2></div>
            <div className={styles.selectorTop}>
                <div className={styles.brandTabs} role="tablist" aria-label="영화관 브랜드">
                    {Object.entries(brands).map(([key, label]) => <button key={key} type="button" role="tab" aria-selected={brand === key} onClick={() => selectBrand(key)}>{label}</button>)}
                </div>
                <button type="button" className={styles.mapSelect} aria-haspopup="dialog" onClick={() => update({ map: '1' })}>
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2.5"/></svg>
                    지도에서 선택
                </button>
            </div>
            <div className={styles.branchPanel}>
                {brand === 'FAVORITES' ? authLoading ? null
                    : !user ? <p className={styles.emptyBranches}>로그인하면 선호극장을 바로 선택할 수 있습니다. <Link to="/login">로그인</Link></p>
                    : !preferences.length ? <p className={styles.emptyBranches}>등록된 선호극장이 없습니다. <Link to="/preferences">선호극장 설정</Link></p>
                    : <div className={styles.theaters} role="group" aria-label="선호극장 선택">{preferences.map(t => <button type="button" key={t.theaterId} aria-pressed={theaterId === String(t.theaterId)} onClick={() => selectTheater(t.theaterId)}>{t.theaterName || `극장 ${t.theaterId}`}</button>)}</div>
                    : <>
                        <QueryStatus query={list} empty={list.data?.items.length === 0} />
                        <div className={styles.theaters} role="group" aria-label={`${brands[brand]} 지점 선택`}>{list.data?.items.map(t => <button type="button" key={t.id} title={t.address} aria-pressed={theaterId === String(t.id)} onClick={() => selectTheater(t.id)}>{t.name}</button>)}</div>
                    </>}
            </div>
            {params.get('map') === '1' && <BookingMapDialog onClose={() => update({ map: null })} onSelect={selectTheater} />}
        </section>
        {theaterId && <>
            <QueryStatus query={theater} />
            <BookingDates booking={booking} />
            {!dateValid && <p role="alert">오늘부터 7일 안의 날짜를 다시 선택해주세요.</p>}
            <div className={styles.sectionHeading}><h2>상영 영화</h2><span>{movies.data?.items.length ?? 0}편</span></div>
            <QueryStatus query={movies} empty={movies.data?.items.length === 0} />
            {movies.data?.items.length > 0 && <QueryStatus query={chart} />}
            {movieId && movies.data && !selectedMovieExists && <p role="alert">해당 날짜의 영화를 다시 선택해주세요.</p>}
            <section aria-label="상영 회차"><ul className={styles.movieList}>{sortedMovies.map(m => <TheaterMovieRow key={m.movieId} movie={m} booking={booking} />)}</ul></section>
            {movies.data?.items.length > 0 && <>
                <p className={styles.note}>조회 시점의 좌석 정보이며 좌석 확보를 보장하지 않습니다.</p>
                {selectedShow && !selectedShow.layoutComplete && <p className={styles.note}>배치 미확인 회차는 자동 선점할 수 없습니다. 스마트예매에서 현재 상태와 대안을 확인할 수 있습니다.</p>}
            </>}
        </>}
    </>);
}
export function MovieBooking({ booking }) {
    const { date, now, rangeFuture, dateValid, from, until, audienceValid, update, shows, valid, enter } = booking;
    const releaseDate = booking.selectedMovie?.releaseDate;
    const beforeRelease = releaseDate && date < releaseDate;
    return <>
        <BookingDates booking={booking} />
        <div className={styles.bookingRail}>
            <div className={styles.movieFields}>
                <AudiencePicker booking={booking} />
                <div className={styles.timePicker}>
                    <TimeRangeMenu key={date} date={date} now={now} from={from} until={until} runningTime={booking.selectedMovie?.runningTime} update={update} />
                </div>
                <button className={ui.primary + ' ' + styles.movieSubmit} disabled={!valid} onClick={() => enter('MOVIE_SMART')}>스마트예매</button>
            </div>
        </div>
        {dateValid && !booking.authLoading && !booking.dayShows.loading && (booking.needsPreferences || booking.dayShows.error || booking.dayShows.data && !booking.hasDayShows) && <div className={styles.availabilityNotice}>
            {booking.needsPreferences ? <p role="status">스마트예매에 사용할 <Link to="/preferences">선호극장을 설정해주세요.</Link></p>
                : booking.dayShows.error ? <QueryStatus query={booking.dayShows} />
                : booking.dayShows.data && !booking.hasDayShows ? <div role="status">
                    <strong>{booking.user ? '선택한 날짜에 선호극장의 상영회차가 없습니다.' : '선택한 날짜에 예매 가능한 상영회차가 없습니다.'}</strong>
                    <p>{beforeRelease ? `${releaseDate.replaceAll('-', '.')} 개봉 예정입니다. ` : ''}다른 날짜를 선택하거나 추후 상영시간표를 확인해주세요.</p>
                </div> : null}
        </div>}
        {!dateValid && <p role="alert">오늘부터 7일 안의 날짜를 선택해주세요.</p>}
        {from && until && !rangeFuture && <p role="alert">현재 시각 이후의 시간 범위를 선택해주세요.</p>}
        {!audienceValid && <p role="alert">관람 인원은 총 1~6명으로 선택해주세요.</p>}
        {shows.error && <QueryStatus query={shows} />}
    </>;
}
export function MovieCatalog({ booking }) {
    const { page, update, list } = booking;
    return (<>
            <div className={styles.sectionHeading}><h2>영화 목록</h2><Link to="/theaters">극장별 예매</Link></div>
            <QueryStatus query={list} empty={list.data?.items.length === 0} />
            <div className={styles.movies}>{list.data?.items.map(m => <MovieCard key={m.id} movie={m} onClick={() => update({ movie: m.id, showtime: null })} />)}</div>
            <Pagination data={list.data} page={page} onChange={value => update({ page: value })} />
        </>);
}
