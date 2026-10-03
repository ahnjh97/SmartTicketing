import InlineDetails from '../components/InlineDetails.jsx';
import GlassButton from '../components/GlassButton.jsx';
import styles from './BookingViews.module.css';
import ui from './BookingComponents.module.css';
import useCatalog from './useCatalog.js';
import { formatRating } from './format.js';
import { Link } from 'react-router-dom';
import { DateCards, MovieCard, Pagination, QueryStatus } from './BookingComponents.jsx';
import BookingMap from './BookingMap.jsx';
import TimeRangeMenu from './TimeRangeMenu.jsx';
import HorizontalRail from '../components/HorizontalRail.jsx';

function BookingDates({ booking }) {
 const { date, today, update, theaterMode } = booking;
 return <DateCards value={date} today={today} onChange={value => update({ date: value, ...(theaterMode ? { movie: null } : {}), showtime: null })} />;
}

function TheaterMovieRow({ movie, booking }) {
    const { theaterId, date, now, params, update, selectedShow, valid, smartReady, enter } = booking;
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
                const selected = params.get('showtime') === String(show.id);
                if (!selected) return <div key={show.id} className={styles.slot}>
                    <button aria-pressed={false} className={ui.showtime} onClick={() => update({ movie: movie.movieId, showtime: show.id })}>
                        <small>{show.screenName}</small>
                        <strong>{show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</strong>
                        <span>{show.availableSeats} / {show.totalSeats}석 {!show.availableSeats ? '매진' : !show.layoutComplete ? '배치 미확인' : ''}</span>
                    </button>
                </div>;

                return <div key={show.id} className={`${styles.slot} ${styles.slotSelected}`}>
                    <div className={`${ui.showtime} ${styles.slotCard}`}>
                        <button className={styles.slotSelection} aria-pressed={true} aria-label={`${show.screenName} ${show.startTime.slice(11, 16)} → ${show.endTime.slice(11, 16)} ${show.availableSeats} / ${show.totalSeats}석 ${!show.availableSeats ? '매진' : !show.layoutComplete ? '배치 미확인' : ''} 선택 해제`} onClick={() => update({ movie: null, showtime: null })}><small>{show.screenName}</small><strong>{show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</strong></button>
                        <div className={styles.slotActions} onClick={e => e.stopPropagation()}>
                            <button aria-label="일반예매" className={styles.slotBtn} disabled={!valid || !selectedShow?.availableSeats} onClick={() => enter('THEATER_NORMAL')}>일반</button>
                            <button aria-label="스마트예매" className={`${styles.slotBtn} ${styles.slotBtnPrimary}`} disabled={!valid || !smartReady} onClick={() => enter('THEATER_SMART')}>스마트</button>
                        </div>
                    </div>
                    <span className={ui.selectedBadge}>선택</span>
                </div>;
            })}</HorizontalRail>
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
                    : <p><InlineDetails items={[<>{from} 이상 ~ {until} 미만</>, `총 ${party}명`]} /></p>}
                <p>{entry === 'THEATER_NORMAL' ? '일반예매' : '스마트예매'}를 위한 선택입니다. 아직 좌석 선점, 결제, 대기 신청은 실행되지 않았습니다.</p>
                {!user && <Link to="/login">로그인하고 이 선택으로 돌아오기</Link>}
            </> : (shows.loading || detail.loading || theater.loading || movies.loading) ? null
                : <p role="alert">선택을 다시 확인해주세요. 날짜가 지났거나 해당 회차/좌석을 이용할 수 없습니다.</p>}
            <div className={ui.actions}><GlassButton onClick={() => update({ entry: null })}>선택 수정</GlassButton></div>
        </section>);
}
export function TheaterBooking({ booking }) {
    const { user, params, expanded, setExpanded, movieId, theaterId, dateValid, page, search, update, selectTheater, brand, selectBrand, list, theater, movies, selectedMovieExists, preferences, selectedShow } = booking;
    const brands = { CGV: 'CGV', LOTTE_CINEMA: '롯데시네마', MEGABOX: '메가박스' };
    const chart = useCatalog(theaterId ? 'main' : null, {});
    const rank = new Map((chart.data?.nowShowing || []).map((m, index) => [m.id, index]));
    const sortedMovies = [...(movies.data?.items || [])]
        .sort((a, b) => (rank.get(a.movieId) ?? 999) - (rank.get(b.movieId) ?? 999));
    return (<>
            <div className={styles.brandTabs} role="tablist" aria-label="영화관 브랜드">
                {Object.entries(brands).map(([key, label]) => <button key={key} type="button" role="tab" data-brand={key} aria-selected={brand === key} onClick={() => selectBrand(key)}>{label}</button>)}
            </div>
            <div className={styles.theaterToolbar}>
                <form className={styles.search} onSubmit={e => { e.preventDefault(); list.retry(); }}>
                    <label className={ui.srOnly} htmlFor="theater-query">극장 이름 또는 주소 검색</label>
                    <input id="theater-query" value={search} maxLength={100} onChange={e => update({ q: e.target.value, page: 0 })} placeholder={`${brands[brand]} 지점 검색`} />
                    <button className={ui.primary} type="submit">검색</button>
                </form>
                <GlassButton className={styles.mapToggle} aria-expanded={params.get('map') === '1'} onClick={() => update({ map: params.get('map') === '1' ? null : '1' })} aria-label={`지도 ${params.get('map') === '1' ? '닫기' : '열기'}`}>지도</GlassButton>
                <span className={styles.note}>선호 극장</span>
                <div className={styles.favorites}>
                    {!user ? <Link to="/login">로그인 후 선호 극장</Link> : !preferences.length ? <span>저장한 선호 극장이 없습니다.</span>
                        : (expanded ? preferences : preferences.slice(0, 3)).map(t => <GlassButton key={t.theaterId} aria-label={`${t.priority}순위 ${t.theaterName || `극장 ${t.theaterId}`}`} aria-pressed={theaterId === String(t.theaterId)} onClick={() => selectTheater(t.theaterId)}>★ {t.theaterName || `극장 ${t.theaterId}`}</GlassButton>)}
                    {preferences.length > 3 && <GlassButton onClick={() => setExpanded(!expanded)}>{expanded ? '접기' : '나머지 선호 극장 보기'}</GlassButton>}
                </div>
            </div>
            {params.get('map') === '1' && <BookingMap theaters={list.data?.items || []} onSelect={selectTheater} />}
            {(!theaterId || search) && <>
                <QueryStatus query={list} empty={list.data?.items.length === 0} />
                {!theaterId && <p className={styles.note}>{brands[brand]} 지점을 선택해주세요. 선호 극장에서도 바로 선택할 수 있습니다.</p>}
                <div className={styles.theaters}>{list.data?.items.map(t => <GlassButton key={t.id} aria-pressed={theaterId === String(t.id)} onClick={() => selectTheater(t.id)}><strong>{t.name}</strong><small>{t.address}</small></GlassButton>)}</div>
                <Pagination data={list.data} page={page} onChange={value => update({ page: value })} />
            </>}
        {theaterId && <>
            <QueryStatus query={theater} />
            {theater.data && <div className={styles.selectedTheater}><strong>{theater.data.name}</strong><span>{theater.data.address}</span><GlassButton onClick={() => update({ brand, theater: null, movie: null, showtime: null })}>극장 변경</GlassButton></div>}
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
                    <TimeRangeMenu key={date} date={date} now={now} from={from} until={until} update={update} />
                </div>
                <button className={ui.primary + ' ' + styles.movieSubmit} disabled={!valid} onClick={() => enter('MOVIE_SMART')}>스마트예매</button>
            </div>
        </div>
        {!dateValid && <p role="alert">오늘부터 7일 안의 날짜를 선택해주세요.</p>}
        {from && until && !rangeFuture && <p role="alert">현재 시각 이후의 시간 범위를 선택해주세요.</p>}
        {!audienceValid && <p role="alert">관람 인원은 총 1~6명으로 선택해주세요.</p>}
        {shows.error && <QueryStatus query={shows} />}
    </>;
}
export function MovieCatalog({ booking }) {
    const { page, update, list } = booking;
    return (<>
            <div className={styles.sectionHeading}><h2>영화 목록</h2><Link to="/theaters">극장별 예매 →</Link></div>
            <QueryStatus query={list} empty={list.data?.items.length === 0} />
            <div className={styles.movies}>{list.data?.items.map(m => <MovieCard key={m.id} movie={m} onClick={() => update({ movie: m.id, showtime: null })} />)}</div>
            <Pagination data={list.data} page={page} onChange={value => update({ page: value })} />
        </>);
}
