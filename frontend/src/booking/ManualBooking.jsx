import WaitingRuleDialog from './WaitingRuleDialog.jsx';
import { useState } from 'react';
import { getSeatLabel } from '../utils/seatLabels.js';
import InlineDetails from '../components/InlineDetails.jsx';
import { useSearchParams, Link } from 'react-router-dom';
import GlassButton from '../components/GlassButton.jsx';
import { QueryStatus } from './BookingComponents.jsx';
import useCatalog from './useCatalog.js';
import useManualHold from './useManualHold.js';
import SeatPicker from './SeatPicker.jsx';
import ReservationPanel from './ReservationPanel.jsx';
import styles from './ManualBooking.module.css';
import ui from './BookingComponents.module.css';

export default function ManualBooking({ booking }) {
    const [params, setParams] = useSearchParams();
    const flow = useManualHold();
    const [confirmCancel, setConfirmCancel] = useState(false);
    const [selectionError, setSelectionError] = useState(null);
    const { selectedShow: show, selectedMovie: movie, theater, shows } = booking;
    const seats = useCatalog(show && !flow.reservationId ? `showtimes/${show.id}/seats` : null);
    const audience = flow.group?.audience || { adultCount: booking.adultCount, youthCount: booking.youthCount };
    const party = audience.adultCount + audience.youthCount;
    const waiting = flow.waiting?.items?.find(item => item.status === 'WAITING');
    const selected = params.has('seats') ? (params.get('seats') || '').split(',').filter(value => /^[1-9]\d*$/.test(value)).map(Number) : waiting?.seatIds || [];
    const validSeats = selected.length === party && new Set(selected).size === selected.length
        && selected.every(id => seats.data?.seats.some(seat => seat.id === id && ['AVAILABLE', 'HOLDING', 'RESERVED'].includes(seat.status)));
    const allAvailable = validSeats && selected.every(id => seats.data.seats.some(seat => seat.id === id && seat.status === 'AVAILABLE'));
    const waitingZone = new Set(selected.map(id => seats.data?.seats.find(seat => seat.id === id)?.position));
    const sameZone = validSeats && waitingZone.size === 1 && !waitingZone.has(undefined) && !waitingZone.has(null);
    const sameWaiting = waiting?.seatIds?.length === selected.length && selected.every(id => waiting.seatIds.includes(id));
    const body = { entryPoint: 'THEATER_NORMAL', movieId: Number(booking.movieId), viewingDate: booking.date,
        partySize: party, selectedShowtimeId: show?.id, audience };
    const change = values => setParams(previous => {
        const next = new URLSearchParams(previous);
        for (const [key,value] of Object.entries(values)) { if (value == null || value === '') next.delete(key); else next.set(key,String(value)); }
        return next;
    }, { replace: true });
    const rejectZone = () => setSelectionError({ code: 'WAITING_SINGLE_ZONE_REQUIRED' });
    const selectSeats = ids => {
        const chosen = ids.map(id => seats.data.seats.find(seat => seat.id === id)).filter(Boolean);
        if (ids.length > selected.length && chosen.some(seat => seat.status !== 'AVAILABLE')
            && new Set(chosen.map(seat => seat.position)).size > 1) {
            rejectZone();
            return;
        }
        setSelectionError(null);
        change({ seats: ids.length ? ids.join(',') : 'none' });
    };
    const submitWaiting = () => {
        if (!sameZone) { rejectZone(); return; }
        flow.hold(body, selected, null, true);
    };
    const reset = () => change({ group: null, reservation: null, seats: null, eligible: null, guardian: null,
        ...(flow.reservation ? { theater: flow.reservation.theaterId, movie: flow.reservation.movieId,
            showtime: flow.reservation.showtimeId, date: flow.reservation.startTime.slice(0,10) } : {}) });
    return <div className={styles.manual}>
        <WaitingRuleDialog error={selectionError || flow.error} onClose={() => setSelectionError(null)} />
        <div className={styles.heading}><h2>일반 예매</h2>
            <GlassButton disabled={flow.busy} onClick={() => booking.update({ entry: null, group: null, reservation: null, seats: null })}>회차 선택으로</GlassButton></div>
        <ol className={ui.steps} aria-label="예매 진행 단계">{['좌석 선택', '선점 후 모의결제', '티켓'].map((label,index) => {
            const step = flow.reservation?.status === 'CONFIRMED' ? 2 : flow.reservation ? 1 : 0;
            return <li key={label} aria-current={index === step ? 'step' : undefined} data-done={index < step}>{label}</li>;
        })}</ol>
        {!flow.user ? <section className={styles.empty}><h3>로그인 후 좌석을 선택할 수 있습니다</h3><p>선택한 회차를 저장해두었습니다.</p><Link to="/login">로그인하고 계속하기</Link></section>
            : flow.loading ? null
            : flow.reservation ? <ReservationPanel flow={flow} onRestart={reset} />
            : flow.reservationId || flow.groupId && !flow.group ? null
            : ['CANCELLED', 'EXPIRED'].includes(flow.group?.status) ? <section className={styles.empty}><p>종료된 예매 요청입니다.</p><GlassButton onClick={reset}>좌석 다시 선택하기</GlassButton></section>
            : show ? <div className={styles.grid}>
                <div className={styles.selection}>
                    <div className={styles.seatHeading}><span aria-live="polite">{selected.length} / {party}석 선택</span><GlassButton disabled={flow.busy || seats.loading} onClick={seats.retry}>좌석 현황 새로고침</GlassButton></div>
                    <QueryStatus query={seats} empty={seats.data?.seats.length === 0} />
                    {seats.data && <SeatPicker seats={seats.data.seats} selected={selected} limit={party >= 1 && party <= 6 ? party : 0}
                        onChange={selectSeats} disabled={flow.busy} allowWaiting />}
                </div>
                <aside className={styles.summary} aria-label="선택한 예매 정보">
                    <h3>{movie?.title}</h3>
                    {waiting && <p role="status">{waiting.seatLabels?.length ? waiting.seatLabels.join(', ') : getSeatLabel(waiting.seatZone)} 대기 중 · 순번 {waiting.aheadCount + 1}번</p>}
                    {waiting && (confirmCancel ? <div><p>이 대기를 취소할까요?</p><GlassButton disabled={flow.busy} onClick={flow.cancelWaiting}>대기 취소 확정</GlassButton><GlassButton onClick={() => setConfirmCancel(false)}>유지하기</GlassButton></div>
                        : <GlassButton onClick={() => setConfirmCancel(true)}>대기 취소</GlassButton>)}
                    <div className={styles.summaryDetails}><InlineDetails items={[theater.data?.name, show.screenName]} /><strong><time dateTime={booking.date}>{booking.date.replaceAll('-', '.')}</time></strong>
                        <span className={styles.showtimeRange}>{show.startTime.slice(11,16)} → {show.endTime.slice(11,16)}</span></div>
                    {selected.length > 0 && <div className={styles.chips}>{selected.map(id => { const seat = seats.data?.seats.find(item => item.id === id); return <span key={id}>{seat ? `${seat.row}${seat.number}` : '확인 중'}</span>; })}</div>}
                    <dl className={styles.audienceSummary} aria-label="관람 인원">
                        <div><dt>성인</dt><dd>{audience.adultCount}<small>명</small></dd></div>
                        <div><dt>청소년</dt><dd>{audience.youthCount}<small>명</small></dd></div>
                        <div><dt>총인원</dt><dd>{party}<small>명</small></dd></div>
                    </dl>
                    {party < 1 || party > 6 ? <p className={styles.error}>총인원을 1~6명으로 선택해주세요.</p> : null}
                    {show.pricePerPerson !== 10000 && <p className={styles.error}>회차 가격을 확인할 수 없어 예매할 수 없습니다.</p>}
                    <button className={`${ui.primary} ${styles.pay}`} disabled={flow.busy || !validSeats || (!allAvailable && sameWaiting) || party < 1 || party > 6 || show.pricePerPerson !== 10000}
                        onClick={() => allAvailable ? flow.hold(body, selected, null, false) : submitWaiting()}>{flow.busy ? '좌석을 확인하고 있습니다…' : !allAvailable && sameWaiting ? '대기 중' : '예매하기'}</button>
                    {waiting && !sameWaiting && validSeats && <p className={styles.hint}>대기 좌석을 변경하면 새 순번으로 등록됩니다.</p>}
                </aside>
            </div> : <><QueryStatus query={shows} /><p role="alert">선택한 회차를 이용할 수 없습니다. 날짜와 회차를 다시 확인해주세요.</p></>}
        {flow.error && <div className={styles.errorBox} role="alert"><strong>요청을 완료하지 못했습니다</strong><p>{flow.error.message}</p>
            <GlassButton disabled={flow.busy} onClick={flow.refresh}>현재 상태 다시 확인</GlassButton><p className={styles.hint}>통신이 끊겼다면 같은 동작을 다시 요청해도 중복 처리되지 않습니다.</p></div>}
    </div>;
}
