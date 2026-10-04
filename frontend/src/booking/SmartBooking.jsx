import { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import GlassButton from '../components/GlassButton.jsx';
import ReservationPanel from './ReservationPanel.jsx';
import useManualHold from './useManualHold.js';
import styles from './SmartBooking.module.css';
import ui from './BookingComponents.module.css';

const unavailable = new Set(['NO_SHOWTIMES', 'SOLD_OUT', 'NO_CONTIGUOUS_SEATS', 'LAYOUT_UNVERIFIED', 'RETRY_EXHAUSTED']);

export default function SmartBooking({ booking }) {
    const flow = useManualHold({ smart: true });
    const failureHeading = useRef(null);
    const started = useRef(false);
    useEffect(() => { if (flow.error) failureHeading.current?.focus(); }, [flow.error]);
    const movieMode = !booking.theaterMode;
    const audience = flow.group?.audience || {
        adultCount: booking.adultCount, youthCount: booking.youthCount,
    };
    const party = audience.adultCount + audience.youthCount;
    const choiceLoading = booking.authLoading || (movieMode ? booking.detail.loading || booking.dayShows.loading
        : booking.theater.loading || booking.movies.loading || booking.shows.loading);
    const ready = Boolean(flow.group || (booking.dateValid && booking.selectedMovie && (movieMode
        ? booking.rangeFuture && booking.audienceValid : booking.selectedShow && booking.audienceValid)));
    const valid = ready && party >= 1 && party <= 6;
    const back = () => {
        flow.resetIntent();
        booking.update({ entry: null, group: null, reservation: null });
    };
    const hold = () => {
        if (!valid || flow.busy) return;
        flow.hold({ entryPoint: movieMode ? 'MOVIE_SMART' : 'THEATER_SMART', movieId: Number(booking.movieId),
            viewingDate: booking.date, partySize: party, audience,
            ...(movieMode ? { startTimeFrom: booking.from, startTimeTo: booking.until } : { selectedShowtimeId: booking.selectedShow?.id }) });
    };
    // Start once after data/auth are ready. Recovery URLs only restore existing requests;
    // failures require an explicit retry and reuse the hold hook's idempotency keys.
    useEffect(() => {
        if (started.current || !flow.user || flow.loading || flow.busy || flow.error || flow.groupId || flow.reservationId || choiceLoading || !valid) return;
        started.current = true;
        hold();
    });
    const choices = unavailable.has(flow.error?.code);
    const step = flow.reservation?.status === 'CONFIRMED' ? 2 : flow.reservation ? 1 : 0;
    return <div className={styles.smart}>
        <header className={styles.heading}><h2>스마트예매</h2><GlassButton disabled={flow.busy} onClick={back}>선택 수정</GlassButton></header>
        <ol className={ui.steps} aria-label="스마트예매 진행 단계">{['좌석 확보', '결제', '나의 티켓'].map((label,index) =>
            <li key={label} aria-current={index === step ? 'step' : undefined} data-done={index < step}>{label}</li>)}</ol>
        {booking.authLoading ? null
            : !flow.user ? <section className={styles.notice}><h3>로그인하고 스마트예매를 시작하세요</h3><p>로그인하면 선택한 조건으로 바로 좌석을 찾습니다.</p><Link to="/login">로그인하고 이 선택으로 돌아오기</Link></section>
            : flow.loading ? null
            : flow.reservation ? <ReservationPanel flow={flow} onRestart={back}/>
            : !flow.error && flow.group && !flow.busy ? <section className={styles.notice}><p>현재 확보된 좌석이 없습니다.</p><GlassButton disabled={!valid} onClick={hold}>다시 좌석 찾기</GlassButton></section>
            : !flow.error && (flow.busy || choiceLoading || (!flow.groupId && !flow.reservationId && valid)) ? <section className={styles.notice} role="status"><h3>가능한 좌석을 찾고 있습니다…</h3><p>성인 {audience.adultCount}명 · 청소년 {audience.youthCount}명 · 총 {party}명</p><p>좌석이 확보되면 결제로 이동합니다.</p></section>
            : !flow.error && !flow.groupId && !flow.reservationId && !valid ? <p role="alert">인원 또는 상영 조건을 확인해주세요. <GlassButton onClick={back}>선택 화면으로 돌아가기</GlassButton></p> : null}
        {flow.error && <section className={styles.failure} role="alert"><p className={styles.eyebrow}>CHOOSE YOUR NEXT STEP</p>
            <h3 ref={failureHeading} tabIndex={-1}>{choices ? '이번에는 자리를 확보하지 못했어요' : '요청을 완료하지 못했습니다'}</h3><p>{flow.error.message}</p>
            <div className={styles.actions}><GlassButton disabled={flow.busy || !valid} onClick={hold}>다시 좌석 찾기</GlassButton>{(flow.groupId || flow.reservationId) && <GlassButton disabled={flow.busy} onClick={flow.refresh}>현재 예약 확인</GlassButton>}
                <GlassButton disabled={flow.busy} onClick={back}>조건 변경</GlassButton>
                <Link to={`/theaters?date=${booking.date}`} onClick={flow.resetIntent}>극장과 회차 직접 선택</Link>
                {flow.error.code === 'NO_THEATER_SCOPE' && <Link to="/preferences" onClick={flow.resetIntent}>선호극장 설정</Link>}
            </div>
        </section>}
    </div>;
}
