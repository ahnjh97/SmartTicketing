import InlineDetails from '../components/InlineDetails.jsx';
import GlassButton from '../components/GlassButton.jsx';
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
                        <strong>{show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</strong>
                        <span>{show.availableSeats} / {show.totalSeats}석 {!show.availableSeats ? '매진' : !show.layoutComplete ? '배치 미확인' : ''}</span>
                    </button>
                </div>;
            })}</HorizontalRail>
            {params.get('movie') === String(movie.movieId) && items.some(show => String(show.id) === params.get('showtime')) &&
                <BookingMethodDialog booking={booking} movie={movie} show={items.find(show => String(show.id) === params.get('showtime'))} />}

        </div>
    </li>;
}

export function BookingConfirmation({ booking }) {
    const { theaterMode, user, date, from, until, party, entry, update, theater, movies, detail, shows, selectedShow, selectedMovie, valid, smartReady } = booking;
    return (<section className={ui.panel + ' ' + styles.confirmation} aria-label="선택 확인">
            <h2>선택 확인 후 예매 준비</h2>
            <QueryStatus query={theaterMode ? theater : detail} />{theaterMode && <QueryStatus query={movies} />}<QueryStatus query={shows} />
            {valid && (entry !== 'THEATER_SMART' || smartReady) ? <>
                <p><InlineDetails items={[selectedMovie?.title, date]} /></p>
                {theaterMode ? <><p><InlineDetails items={[theater.data?.name, <>{selectedShow.startTime.slice(11, 16)} → {selectedShow.endTime.slice(11, 16)}</>]} /></p><p>인원은 다음 단계에서 선택합니다.</p></>
                    : <p><InlineDetails items={[<>시작시간 {from} ~ {until} (양 끝 포함)</>, `총 ${party}명`]} /></p>}
                <p>{entry === 'THEATER_NORMAL' ? '일반예매' : '스마트예매'}를 위한 선택입니다. 아직 좌석 선점, 결제, 대기 신청은 실행되지 않았습니다.</p>
                {!user && <Link to="/login">로그인하고 이 선택으로 돌아오기</Link>}
            </> : (shows.loading || detail.loading || theater.loading || movies.loading) ? null
                : <p role="alert">선택을 다시 확인해주세요. 날짜가 지났거나 해당 회차/좌석을 이용할 수 없습니다.</p>}
            <div className={ui.actions}><GlassButton onClick={() => update({ entry: null })}>선택 수정</GlassButton></div>
        </section>);
}
export function TheaterBooking({ booking }) {
    const { user, authLoading, params, movieId, theaterId, dateValid, update, selectTheater, brand, selectBrand, list, theater, movies, selectedMovieExists, preferences, selectedShow } = booking;
    const brands = { FAVORITES: '선호극장', CGV: 'CGV', LOTTE_CINEMA: '롯데시네마', MEGABOX: '메가박스' };
    const chart = useCatalog(theaterId ? 'main' : null, {});
    const rank = new Map((chart.data?.nowShowing || []).map((m, index) => [m.id, index]));
    const sortedMovies = [...(movies.data?.items || [])]
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
                {brand === 'FAVORITES' ? authLoading ? <p role="status" className={styles.note}>선호극장을 불러오는 중…</p>
                    : !user ? <p className={styles.emptyBranches}>로그인하면 선호극장을 바로 선택할 수 있습니다. <Link to="/login">로그인</Link></p>
                    : !preferences.length ? <p className={styles.emptyBranches}>등록된 선호극장이 없습니다. <Link to="/preferences">선호극장 설정</Link></p>
                    : <div className={styles.theaters} role="group" aria-label="선호극장 선택">{preferences.map((t, index) => <button type="button" key={t.theaterId} aria-label={`${index + 1}순위 ${t.theaterName || `극장 ${t.theaterId}`}`} aria-pressed={theaterId === String(t.theaterId)} onClick={() => selectTheater(t.theaterId)}><span className={styles.preferenceRank}>{index + 1}</span>{t.theaterName || `극장 ${t.theaterId}`}</button>)}</div>
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
            {movieId && movies.data && !selectedMovieExists && <p role="alert">해당 날짜의 영화를 다시 선택해주세요.</p>}
            <section aria-label="상영 회차"><ul className={styles.movieList}>{sortedMovies.map(m => <TheaterMovieRow key={m.movieId} movie={m} booking={booking} />)}</ul></section>
            {movies.data?.items.length > 0 && <>
                <p className={styles.note}>조회 시점의 좌석 정보이며 좌석 확보를 보장하지 않습니다. 인원은 다음 단계에서 선택합니다.</p>
                {selectedShow && !selectedShow.layoutComplete && <p className={styles.note}>배치 미확인 회차는 자동 선점할 수 없습니다. 스마트예매에서 현재 상태와 대안을 확인할 수 있습니다.</p>}
            </>}
        </>}
    </>);
}
export function MovieBooking({ booking }) {
    const { date, now, rangeFuture, dateValid, from, until, adultCount, youthCount, audienceValid, update, shows, valid, enter } = booking;
    const total = adultCount + youthCount;
    const changeCount = (field, value) => update({ adult: adultCount, youth: youthCount, [field]: value, party: total + value - (field === 'adult' ? adultCount : youthCount) });
    const releaseDate = booking.selectedMovie?.releaseDate;
    const beforeRelease = releaseDate && date < releaseDate;
    return <>
        <BookingDates booking={booking} />
        <div className={styles.bookingRail}>
            <div className={styles.movieFields}>
                <fieldset className={styles.audiencePicker}>
                    <legend>관람 인원 <span>최대 6명</span></legend>
                    <div className={styles.audienceRow}>{[['adult', '성인', adultCount], ['youth', '청소년', youthCount]].map(([field, label, value]) =>
                        <div className={styles.counter} key={field}>
                            <span>{label}</span>
                            <div className={styles.counterControls}>
                            <button type="button" aria-label={label + ' 인원 줄이기'} disabled={value <= 0 || total <= 1} onClick={() => changeCount(field, value - 1)}>−</button>
                            <output aria-label={label + ' 인원'} aria-live="polite">{value}</output>
                            <button type="button" aria-label={label + ' 인원 늘리기'} disabled={total >= 6} onClick={() => changeCount(field, value + 1)}>+</button>
                            </div>
                        </div>)}<div className={styles.partyTotal}><span>총인원</span><output aria-label="총인원" aria-live="polite"><strong>{total}</strong>명</output></div></div>
                </fieldset>
                <div className={styles.timePicker}><span className={styles.fieldLabel}>상영 시작 시간</span>
                    <TimeRangeMenu key={date} date={date} now={now} from={from} until={until} runningTime={booking.selectedMovie?.runningTime} update={update} />
                </div>
                <button className={ui.primary + ' ' + styles.movieSubmit} disabled={!valid} onClick={() => enter('MOVIE_SMART')}>스마트예매</button>
            </div>
        </div>
        {dateValid && <div className={styles.availabilityNotice}>
            {booking.authLoading || booking.dayShows.loading ? <p role="status">상영회차를 확인하고 있습니다. 인원과 시간은 먼저 선택할 수 있습니다.</p>
                : booking.needsPreferences ? <p role="status">스마트예매에 사용할 <Link to="/preferences">선호극장을 설정해주세요.</Link></p>
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
