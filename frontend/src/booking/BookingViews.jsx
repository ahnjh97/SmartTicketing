import InlineDetails from '../components/InlineDetails.jsx';
import GlassButton from '../components/GlassButton.jsx';
import styles from './BookingViews.module.css';
import ui from './BookingComponents.module.css';
import useCatalog from './useCatalog.js';
import { formatRating } from './format.js';
import { Link } from 'react-router-dom';
import { availability, validParty } from './state.js';
import { BookingButtons, DateCards, MovieCard, Pagination, QueryStatus, ShowtimeCard } from './BookingComponents.jsx';
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
                    <button className={ui.showtime} onClick={() => update({ movie: movie.movieId, showtime: show.id })}>
                        <small>{show.screenName}</small>
                        <strong>{show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</strong>
                        <span>{show.availableSeats} / {show.totalSeats}석</span>
                    </button>
                </div>;

                return <div key={show.id} className={`${styles.slot} ${styles.slotSelected}`}>
                    <div className={`${ui.showtime} ${styles.slotCard}`} onClick={() => update({ movie: null, showtime: null })}>
                        <small>{show.screenName}</small>
                        <strong>{show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</strong>
                        <div className={styles.slotActions} onClick={e => e.stopPropagation()}>
                            <button className={styles.slotBtn} disabled={!valid || !selectedShow?.availableSeats} onClick={() => enter('THEATER_NORMAL')}>일반</button>
                            <button className={`${styles.slotBtn} ${styles.slotBtnPrimary}`} disabled={!valid || !smartReady} onClick={() => enter('THEATER_SMART')}>스마트</button>
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
                {theaterMode ? <><p><InlineDetails items={[theater.data?.name, <>{selectedShow.startTime.slice(11, 16)} → {selectedShow.endsNextDay && '익일 '}{selectedShow.endTime.slice(11, 16)}</>]} /></p><p>인원은 다음 단계에서 선택합니다.</p></>
                    : <p><InlineDetails items={[<>{from} 이상 ~ {until} 미만{from > until && ' (다음 날)'}</>, `총 ${party}명`]} /></p>}
                <p>{entry === 'THEATER_NORMAL' ? '일반예매' : '스마트예매'}를 위한 선택입니다. 아직 좌석 선점, 결제, 대기 신청은 실행되지 않았습니다.</p>
                {!user && <Link to="/login">로그인하고 이 선택으로 돌아오기</Link>}
            </> : (shows.loading || detail.loading || theater.loading || movies.loading) ? null
                : <p role="alert">선택을 다시 확인해주세요. 날짜가 지났거나 해당 회차/좌석을 이용할 수 없습니다.</p>}
            <div className={ui.actions}><GlassButton onClick={() => update({ entry: null })}>선택 수정</GlassButton></div>
        </section>);
}
export function TheaterBooking({ booking }) {
    const { user, params, expanded, setExpanded, movieId, theaterId, dateValid, page, search, update, selectTheater, list, theater, movies, selectedMovieExists, preferences, selectedShow } = booking;
    const chart = useCatalog(theaterId ? 'main' : null, {});
    const rank = new Map((chart.data?.nowShowing || []).map((m, index) => [m.id, index]));
    const sortedMovies = [...(movies.data?.items || [])]
        .sort((a, b) => (rank.get(a.movieId) ?? 999) - (rank.get(b.movieId) ?? 999));
    return (<>
            <div className={styles.theaterToolbar}>
                <form className={styles.search} onSubmit={e => { e.preventDefault(); list.retry(); }}>
                    <label className={ui.srOnly} htmlFor="theater-query">극장 이름 또는 주소 검색</label>
                    <input id="theater-query" value={search} maxLength={100} onChange={e => update({ q: e.target.value, page: 0 })} placeholder="극장명 검색" />
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
                {!theaterId && <p className={styles.note}>검색 또는 선호 극장에서 극장을 선택해주세요.</p>}
                <div className={styles.theaters}>{list.data?.items.map(t => <GlassButton key={t.id} aria-pressed={theaterId === String(t.id)} onClick={() => selectTheater(t.id)}><strong>{t.name}</strong><small>{t.address}</small></GlassButton>)}</div>
                <Pagination data={list.data} page={page} onChange={value => update({ page: value })} />
            </>}
        {theaterId && <>
            <QueryStatus query={theater} />
            {theater.data && <div className={styles.selectedTheater}><strong>{theater.data.name}</strong><span>{theater.data.address}</span><GlassButton onClick={() => update({ theater: null, movie: null, showtime: null })}>극장 변경</GlassButton></div>}
            <BookingDates booking={booking} />
            {!dateValid && <p role="alert">오늘부터 7일 안의 날짜를 다시 선택해주세요.</p>}
            <div className={styles.sectionHeading}><h2>상영 영화</h2><span>{movies.data?.items.length ?? 0}편</span></div>
            <QueryStatus query={movies} empty={movies.data?.items.length === 0} />
            {movieId && movies.data && !selectedMovieExists && <p role="alert">해당 날짜의 영화를 다시 선택해주세요.</p>}
            <ul className={styles.movieList}>{sortedMovies.map(m => <TheaterMovieRow key={m.movieId} movie={m} booking={booking} />)}</ul>
            {movies.data?.items.length > 0 && <>
                <p className={styles.note}>조회 시점의 좌석 정보이며 좌석 확보를 보장하지 않습니다. 인원은 다음 단계에서 선택합니다.</p>
                {selectedShow && !selectedShow.layoutComplete && <p className={styles.note}>배치 미확인 회차는 자동 선점할 수 없습니다. 스마트예매에서 현재 상태와 대안을 확인할 수 있습니다.</p>}
            </>}
        </>}
    </>);
}
export function MovieBooking({ booking }) {
    const { date, now, rangeFuture, dateValid, from, until, party, rangeValid, update, shows, items, valid, enter } = booking;
    return (<>
            <BookingDates booking={booking} />
            {!dateValid && <p role="alert">오늘부터 7일 안의 날짜를 다시 선택해주세요.</p>}
            <div className={styles.fields}>
                <label>인원 선택<select aria-label="총인원" value={validParty(party) ? party : ''} onChange={e => update({ party: e.target.value })}><option value="" disabled>인원 선택</option>{[1, 2, 3, 4, 5, 6].map(n => <option key={n} value={n}>{n}명</option>)}</select></label>
                <TimeRangeMenu key={date} date={date} now={now} from={from} until={until} update={update} />
                <BookingButtons disabled={!valid} onEnter={enter} />
            </div>
            <div className={styles.conditionsNote}>
                <p>서울 시간 기준이며, 하한은 포함하고 상한은 제외합니다. 상한이 더 이르면 다음 날까지 검색합니다. 시간 초기화 시 남은 전체 회차를 조회합니다. 스마트예매 진입에는 시간 범위가 필요합니다.</p>
                {from && until && rangeValid && !rangeFuture && <p role="alert">이미 지난 시작 시간입니다. 현재 시각 이후로 다시 선택해주세요.</p>}
                {from && until && rangeValid && until < from && <p>종료 범위는 다음 날 {until} 미만입니다.</p>}
                {!rangeValid && <p role="alert">시작 시간과 종료 시간을 30분 단위로 선택해주세요.</p>}
                {!validParty(party) && <p>총인원을 1~6명으로 선택해주세요.</p>}
                <details><summary>인원과 좌석 조건 안내</summary><p>최대 6명. 스마트예매는 전체 연석을 우선하며, 없으면 4명은 2+2, 5명은 2+3, 6명은 2+2+2, 3+3, 2+4로 나눠 앉을 수 있습니다. 각 묶음은 연석이며 서로 다른 행이나 통로 구간도 가능합니다. 같은 회차에서 전원 좌석을 한 번에 확보합니다.</p></details>
                <QueryStatus query={shows} empty={Boolean(shows.data && !items.length)} />
                {items.length > 0 && <details className={styles.inventory}><summary>조회한 회차와 좌석 상태 {items.length}개</summary>
                    <div className={ui.showtimes}>{items.map(show => <ShowtimeCard key={show.id} show={show} label={availability(show, validParty(party) ? Number(party) : null)} />)}</div>
                    <p>조회 결과는 좌석 확보를 보장하지 않습니다. 스마트예매에서 선호극장 안의 회차와 좌석을 자동 선택하며, 확보하지 못하면 대안을 안내합니다.</p>
                </details>}
            </div>
        </>);
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
