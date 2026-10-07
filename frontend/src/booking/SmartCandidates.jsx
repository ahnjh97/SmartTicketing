import WaitingRuleDialog from './WaitingRuleDialog.jsx';
import { Link } from 'react-router-dom';
import { useState } from 'react';
import GlassButton from '../components/GlassButton.jsx';
import SeatZoneMap from '../components/SeatZoneMap.jsx';
import ReservationPanel from './ReservationPanel.jsx';
import useSmartCandidates from './useSmartCandidates.js';
import useReservationClock from './useReservationClock.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import styles from './SmartBooking.module.css';
import ui from './BookingComponents.module.css';
import reservationStyles from './ManualBooking.module.css';

const titles = { FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' };
const status = candidate => {
    const reservation = candidate.payment?.reservation;
    if (reservation) return { PENDING: '좌석 확보 · 결제 가능', CONFIRMED: '결제 완료', CANCELLED: '예매 취소', EXPIRED: '선점 만료' }[reservation.status];
    const queue = candidate.waiting?.items?.[0];
    return { WAITING: '구역 대기 중', HOLDING: '좌석 확인 중', CANCELLED: '대기 취소', EXPIRED: '대기 종료' }[queue?.status] || '후보 확인 중';
};
const time = value => `${value.slice(5,10).replace('-', '/')} ${value.slice(11,16)}`;

export default function SmartCandidates({ booking }) {
    const movieMode = !booking.theaterMode;
    const audience = { adultCount: booking.adultCount, youthCount: booking.youthCount };
    const ready = Boolean(!booking.authLoading && booking.user && booking.dateValid && booking.selectedMovie && booking.audienceValid &&
        (movieMode ? booking.rangeFuture && !booking.detail.loading && !booking.dayShows.loading : booking.selectedShow && !booking.shows.loading));
    const body = { entryPoint: movieMode ? 'MOVIE_SMART' : 'THEATER_SMART', movieId: Number(booking.movieId), viewingDate: booking.date,
        partySize: audience.adultCount + audience.youthCount, audience,
        ...(movieMode ? { startTimeFrom: booking.from, startTimeTo: booking.until } : { selectedShowtimeId: booking.selectedShow?.id }) };
    const flow = useSmartCandidates(booking.user, body, ready);
    const candidates = [...(flow.data?.candidates || [])].sort((a,b) => {
        const held = candidate => candidate.payment?.reservation?.status === 'PENDING';
        return Number(held(b)) - Number(held(a)) || (held(a) && held(b) ? (a.preferenceRank ?? 99) - (b.preferenceRank ?? 99) : 0);
    });
    const active = candidate => candidate.payment?.reservation?.status === 'PENDING' || candidate.waiting?.items?.some(q => ['WAITING', 'PAUSED'].includes(q.status));
    const sidebar = candidates.filter(active);
    const selected = candidates.find(c => String(c.groupId) === flow.selectedId) || (!flow.selectedId ? sidebar[0] : null);
    const step = selected?.payment?.reservation?.status === 'CONFIRMED' ? 2 : selected?.payment?.reservation ? 1 : 0;
    const back = () => booking.update({ entry: null, smart: null, plan: null, candidate: null, group: null, reservation: null });
    return <div className={styles.smart}>
        <WaitingRuleDialog error={flow.error} />
        <header className={styles.heading}><h2>스마트예매</h2>
            <GlassButton disabled={flow.busy} onClick={back}>선택 수정</GlassButton></header>
        <ol className={ui.steps} aria-label="스마트예매 진행 단계">{['좌석 선정', '결제', '나의 티켓'].map((label,index) =>
            <li key={label} aria-current={index === step ? 'step' : undefined} data-done={index < step}>{label}</li>)}</ol>
        {!booking.authLoading && !booking.user ? <div className={styles.notice}><h3>로그인하고 스마트예매를 시작하세요</h3><Link to="/login">로그인하고 이 선택으로 돌아오기</Link></div>
            : !flow.data && !flow.error ? <div className={styles.notice} role="status">{flow.managing || ready || flow.busy ? '조건에 맞는 후보와 구역별 대기를 확인하고 있습니다…' : '영화, 날짜, 시간과 인원을 확인해주세요.'}</div> : null}
        {flow.error && <div className={styles.failure} role="alert"><p>{flow.error.message}</p>
            <GlassButton disabled={flow.busy} onClick={flow.managing ? flow.refresh : flow.create}>다시 확인</GlassButton></div>}
        {flow.data && <div className={styles.candidateLayout}>
            <aside className={styles.candidateSidebar} aria-label="좌석 선정 후보">
                <div className={styles.sidebarHeading}><h3>대기 및 선점</h3><span>{sidebar.length}개</span></div>
                {sidebar.map((candidate, index) => <button type="button" key={candidate.groupId}
                    aria-label={`${index + 1}번 ${titles[candidate.kind]} · ${getSeatLabel(candidate.zone)} · ${candidate.movieTitle} · ${candidate.theaterName} · ${time(candidate.startTime)}`}
                    className={styles.candidateCard} disabled={flow.busy} aria-pressed={selected?.groupId === candidate.groupId} onClick={() => flow.select(candidate.groupId)}>
                    <span className={styles.cardTop}>
                        <strong>{titles[candidate.kind]}</strong>
                        {candidate.payment?.reservation?.status === 'PENDING' && <CandidateDeadline reservation={candidate.payment.reservation} receivedAt={flow.receivedAt}/>}
                        {!candidate.payment?.reservation && <span className={styles.candidateStatus}>{candidate.waiting?.items?.[0]?.status === 'WAITING' ? `대기 ${candidate.waiting.items[0].queueNumber}번` : '대기 일시정지'}</span>}
                    </span>
                    <SeatZoneMap zone={candidate.zone} className={styles.zoneMap}/>
                </button>)}
                {!sidebar.length && <p className={styles.sidebarNote}>진행 중인 스마트 대기·선점이 없습니다.</p>}
                <Link to="/bookings">내 대기 및 선점 모두 보기</Link>
            </aside>
            <section className={styles.candidateDetail} aria-label="선택한 후보 상세">
                {selected ? <>
                    {selected.payment?.reservation ? <ReservationPanel key={selected.groupId} flow={{ reservation: selected.payment.reservation,
                        payment: selected.payment, receivedAt: flow.receivedAt, busy: flow.busy,
                        pay: fail => flow.mutate(selected, 'pay', fail), cancel: () => flow.mutate(selected, 'cancel'), refresh: flow.refresh }} onRestart={back}/>
                        : <CandidateWaiting key={selected.groupId} candidate={selected} flow={flow}/>}
                </> : <div className={styles.notice}>현재 남아 있는 후보가 없습니다. 새 조건으로 다시 찾아주세요.</div>}
            </section>
        </div>}
    </div>;
}

function CandidateDeadline({ reservation, receivedAt }) {
    const { remaining } = useReservationClock(reservation, receivedAt);
    return <span className={styles.candidateStatus}>{remaining > 0 ? `선점 ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}` : '선점 만료'}</span>;
}

function CandidateWaiting({ candidate, flow }) {
    const [confirmCancel, setConfirmCancel] = useState(false);
    const queue = candidate.waiting?.items?.[0];
    const waiting = queue?.status === 'WAITING';
    return <section className={reservationStyles.reservation} aria-label="후보 구역 대기">
        <div className={reservationStyles.resultIcon} aria-hidden="true">{waiting ? '◷' : '—'}</div>
        <h2>{status(candidate)}</h2>
        {waiting ? <><div className={reservationStyles.clock}><span>{getSeatLabel(candidate.zone)} 발급번호</span><strong>{queue.queueNumber}<small>번</small></strong></div>
            <div className={reservationStyles.ticket}>
                <div className={reservationStyles.ticketMain}><div><h3>{candidate.movieTitle}</h3>
                    <p>{candidate.theaterName} · {candidate.screenName}</p><p>{time(candidate.startTime)} · {candidate.partySize}명</p>
                    <div className={reservationStyles.ticketSeats}><span>{titles[candidate.kind]}</span><strong>{getSeatLabel(candidate.zone) || '좌석 자동 배정'}</strong></div>
                </div></div>
            </div>
            <p>같은 구역에서 앞선 대기 신청은 <b>{queue.aheadCount}건</b>입니다. 인원에 맞는 좌석이 나오면 자동으로 확보하고 알려드려요.</p>
            <details className={styles.waitingHelp}><summary>대기 배정 안내</summary>
                <p>좌석우선 → 균형추천 → 빠른예매 순으로 확인합니다. 앞선 후보는 대기하고, 처음 예매 가능한 후보만 선점하며 이후 후보는 신청하지 않습니다. 모두 매진이면 모두 대기합니다.</p>
                <p>회차마다 한 구역에만 대기하며, 일반예매와 같은 구역 대기 순서를 적용합니다.</p>
                <p>각 묶음은 연석으로 배정됩니다. 6명은 전체 연석 → 3+3 → 2+4 → 2+2+2 순서로 찾습니다.</p>
                <p>앞 신청의 인원에 맞지 않는 좌석은 뒤 신청에 먼저 배정될 수 있습니다. 확보 후 결제 시간은 5분입니다.</p>
            </details>
            <div className={styles.actions}>{confirmCancel ? <><span>이 후보의 대기만 취소할까요?</span>
                <GlassButton disabled={flow.busy} onClick={() => flow.mutate(candidate, 'cancel-waiting')}>이 후보 대기 취소 확정</GlassButton>
                <GlassButton disabled={flow.busy} onClick={() => setConfirmCancel(false)}>계속 기다리기</GlassButton></>
                : <GlassButton disabled={flow.busy} onClick={() => setConfirmCancel(true)}>이 후보 대기 취소</GlassButton>}</div></>
            : <p>다른 후보의 대기와 예매는 계속 유지됩니다.</p>}
        <GlassButton disabled={flow.busy} onClick={flow.refresh}>현재 상태 확인</GlassButton>
    </section>;
}
