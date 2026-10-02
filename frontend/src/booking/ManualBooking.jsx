import InlineDetails from '../components/InlineDetails.jsx';
import { useSearchParams, Link } from 'react-router-dom';
import GlassButton from '../components/GlassButton.jsx';
import { QueryStatus } from './BookingComponents.jsx';
import useCatalog from './useCatalog.js';
import useManualHold from './useManualHold.js';
import WaitingPanel from './WaitingPanel.jsx';
import AudienceFields from './AudienceFields.jsx';
import SeatPicker from './SeatPicker.jsx';
import ReservationPanel from './ReservationPanel.jsx';
import styles from './ManualBooking.module.css';
import ui from './BookingComponents.module.css';

const count = value => /^[0-6]$/.test(value ?? '') ? Number(value) : 0;
export default function ManualBooking({ booking }) {
    const [params, setParams] = useSearchParams();
    const flow = useManualHold();
    const { selectedShow: show, selectedMovie: movie, theater, shows } = booking;
    const seats = useCatalog(show && !flow.reservationId ? `showtimes/${show.id}/seats` : null);
    const audience = flow.group?.audience || { adultCount: count(params.get('adult')), youthCount: count(params.get('youth')),
        companionsEligible: params.get('eligible') === '1', guardianAccompanying: params.get('guardian') === '1' };
    const party = audience.adultCount + audience.youthCount;
    const selected = (params.get('seats') || '').split(',').filter(value => /^[1-9]\d*$/.test(value)).map(Number);
    const validSeats = selected.length === party && new Set(selected).size === selected.length
        && selected.every(id => seats.data?.seats.some(seat => seat.id === id && seat.status === 'AVAILABLE'));
    const change = values => setParams(previous => {
        const next = new URLSearchParams(previous);
        for (const [key,value] of Object.entries(values)) { if (value == null || value === '') next.delete(key); else next.set(key,String(value)); }
        return next;
    }, { replace: true });
    const reset = () => change({ group: null, reservation: null, seats: null, eligible: null, guardian: null,
        ...(flow.reservation ? { theater: flow.reservation.theaterId, movie: flow.reservation.movieId,
            showtime: flow.reservation.showtimeId, date: flow.reservation.startTime.slice(0,10) } : {}) });
    const chooseAudience = values => {
        const names = { adultCount: 'adult', youthCount: 'youth', companionsEligible: 'eligible', guardianAccompanying: 'guardian' };
        change(Object.fromEntries([...Object.entries(values).map(([key,value]) => [names[key], typeof value === 'boolean' ? (value ? '1' : null) : value]),
            ...('adultCount' in values || 'youthCount' in values ? [['seats', null]] : [])]));
    };
    return <div className={styles.manual}>
        <div className={styles.heading}><div><p className={styles.eyebrow}>MAKE IT A MOVIE NIGHT</p><h2>{flow.reservation ? '나의 예매' : '나의 자리를 선택하세요'}</h2></div>
            <GlassButton disabled={flow.busy} onClick={() => booking.update({ entry: null, group: null, reservation: null, seats: null })}>회차 선택으로</GlassButton></div>
        <ol className={styles.steps} aria-label="예매 진행 단계">{['인원과 좌석', '선점 후 모의결제', '티켓'].map((label,index) => {
            const step = flow.reservation?.status === 'CONFIRMED' ? 2 : flow.reservation ? 1 : 0;
            return <li key={label} aria-current={index === step ? 'step' : undefined} data-done={index < step}><span>{index < step ? '✓' : `0${index+1}`}</span>{label}</li>;
        })}</ol>
        {!flow.user ? <section className={styles.empty}><h3>로그인 후 좌석을 선택할 수 있습니다</h3><p>선택한 회차를 저장해두었습니다.</p><Link to="/login">로그인하고 계속하기 →</Link></section>
            : flow.loading ? <div className={styles.skeleton} role="status">서버에서 예약을 복원하고 있습니다…</div>
            : flow.reservation ? <ReservationPanel flow={flow} onRestart={reset} />
            : flow.reservationId || flow.groupId && !flow.group ? null
            : show ? <div className={styles.grid}>
                <div className={styles.selection}>
                    <AudienceFields {...audience} onChange={chooseAudience} disabled={flow.busy || Boolean(flow.group)} />
                    <div className={styles.seatHeading}><h3><span>02</span> 관람할 좌석</h3><span aria-live="polite">{selected.length} / {party}석 선택</span></div>
                    <QueryStatus query={seats} empty={seats.data?.seats.length === 0} />
                    {seats.data && <SeatPicker seats={seats.data.seats} selected={selected} limit={party >= 1 && party <= 6 ? party : 0}
                        onChange={ids => change({ seats: ids.join(',') })} disabled={flow.busy} />}
                </div>
                <aside className={styles.summary} aria-label="선택한 예매 정보">
                    <p className={styles.eyebrow}>YOUR MOVIE</p><h3>{movie?.title}</h3><p>{movie?.rating || '등급 미확인'}</p>
                    <div className={styles.summaryDetails}><InlineDetails items={[theater.data?.name, show.screenName]} /><strong>{booking.date}</strong>
                        <span>{show.startTime.slice(11,16)} → {show.endsNextDay && '익일 '}{show.endTime.slice(11,16)}</span></div>
                    <div className={styles.chips}>{selected.length ? selected.map(id => { const seat = seats.data?.seats.find(item => item.id === id); return <span key={id}>{seat ? `${seat.row}${seat.number}` : '확인 중'}</span>; }) : <p>마음에 드는 좌석을 선택하세요</p>}</div>
                    <p><InlineDetails items={[`성인 ${audience.adultCount}명`, `청소년 ${audience.youthCount}명`]} /></p>
                    {party < 1 || party > 6 ? <p className={styles.error}>총인원을 1~6명으로 선택해주세요.</p> : null}
                    <p className={styles.hint}>최종 금액은 좌석 선점 시 서버가 계산합니다.</p>
                    {show.pricePerPerson !== 10000 && <p className={styles.error}>회차 가격을 확인할 수 없어 예매할 수 없습니다.</p>}
                    <button className={`${ui.primary} ${styles.pay}`} disabled={flow.busy || !validSeats || !audience.companionsEligible || party < 1 || party > 6 || show.pricePerPerson !== 10000}
                        onClick={() => flow.hold({ entryPoint: 'THEATER_NORMAL', movieId: Number(booking.movieId), viewingDate: booking.date,
                            partySize: party, selectedShowtimeId: show.id, audience }, selected)}>{flow.busy ? '좌석을 확인하고 있습니다…' : '선택한 좌석 5분 선점 →'}</button>
                    <p className={styles.hint}>다음 단계에서 모의결제를 진행합니다.</p>
                    <GlassButton disabled={flow.busy} onClick={seats.retry}>좌석 현황 새로고침</GlassButton>
                </aside>
            </div> : <><QueryStatus query={shows} /><p role="alert">선택한 회차를 이용할 수 없습니다. 날짜와 회차를 다시 확인해주세요.</p></>}
        {flow.error && <div className={styles.errorBox} role="alert"><strong>요청을 완료하지 못했습니다</strong><p>{flow.error.message}</p>
            <GlassButton disabled={flow.busy} onClick={flow.refresh}>현재 상태 다시 확인</GlassButton><p className={styles.hint}>통신이 끊겼다면 같은 동작을 다시 요청해도 중복 처리되지 않습니다.</p></div>}
        {flow.user && flow.group && <WaitingPanel key={`${flow.user.id}:${flow.group.id}`} flow={flow}/>}
    </div>;
}
