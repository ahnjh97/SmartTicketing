import { formatShowDate, formatShowTime, formatShowtime as time } from '../utils/showtimeFormat.js';
import WaitingRuleDialog from './WaitingRuleDialog.jsx';
import { Link } from 'react-router-dom';
import { useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import GlassButton from '../components/GlassButton.jsx';
import InlineDetails from '../components/InlineDetails.jsx';
import SeatZoneMap from '../components/SeatZoneMap.jsx';
import ReservationPanel, { CancelDialog } from './ReservationPanel.jsx';
import useSmartCandidates from './useSmartCandidates.js';
import useReservationClock from './useReservationClock.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import styles from './SmartBooking.module.css';
import ui from './BookingComponents.module.css';
import reservationStyles from './ManualBooking.module.css';

const titles = { FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' };
const narrowScreen = () => window.matchMedia?.('(max-width: 800px)').matches ?? false;
const subscribeScreen = callback => {
    const query = window.matchMedia?.('(max-width: 800px)');
    query?.addEventListener('change', callback);
    return () => query?.removeEventListener('change', callback);
};
const status = candidate => {
    const reservation = candidate.payment?.reservation;
    if (reservation) return { PENDING: '좌석 확보 · 결제 가능', CONFIRMED: '결제 완료', CANCELLED: '예매 취소', EXPIRED: '선점 만료' }[reservation.status];
    const queue = candidate.waiting?.items?.[0];
    return { WAITING: '대기 중', HOLDING: '좌석 확인 중', CANCELLED: '대기 취소', EXPIRED: '대기 종료' }[queue?.status] || '후보 확인 중';
};


export default function SmartCandidates({ booking }) {
    const narrow = useSyncExternalStore(subscribeScreen, narrowScreen);
    const [narrowExpanded, setNarrowExpanded] = useState(false);
    const expanded = !narrow || narrowExpanded;
    const toggleList = useRef(null);
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
        const previous = candidate => flow.candidateIds !== null && !flow.candidateIds.includes(String(candidate.groupId));
        if (previous(a) !== previous(b)) return Number(previous(a)) - Number(previous(b));
        return Number(held(b)) - Number(held(a)) || (held(a) && held(b) ? (a.preferenceRank ?? 99) - (b.preferenceRank ?? 99) : 0);
    });
    const active = candidate => candidate.payment?.reservation?.status === 'PENDING' || candidate.waiting?.items?.some(q => ['WAITING', 'PAUSED'].includes(q.status));
    const sidebar = candidates.filter(active);
    const batchIds = flow.data?.batches?.length ? flow.data.batches : flow.candidateIds ? [flow.candidateIds] : [];
    const groupedIds = new Set(batchIds.flat().map(String));
    const sidebarGroups = batchIds.map(ids => sidebar.filter(candidate => ids.some(id => String(id) === String(candidate.groupId))))
        .concat([sidebar.filter(candidate => !groupedIds.has(String(candidate.groupId)))])
        .filter(items => items.length);
    const reservationId = booking.params.get('reservation');
    const selected = candidates.find(c => String(c.payment?.reservation?.id) === reservationId)
        || candidates.find(c => String(c.groupId) === flow.selectedId)
        || (!flow.selectedId ? sidebarGroups[0]?.[0] || candidates[0] : null);
    const step = selected?.payment?.reservation?.status === 'CONFIRMED' ? 2 : selected?.payment?.reservation ? 1 : 0;
    const back = () => booking.update({ entry: null, smart: null, plan: null, candidate: null, candidates: null, group: null, reservation: null });
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
                <div className={styles.sidebarHeading}><h3>대기 및 선점</h3>
                    {narrow ? <button ref={toggleList} type="button" className={styles.listToggle} aria-expanded={expanded} aria-controls="smart-candidate-list"
                        aria-label={`대기 및 선점 ${sidebar.length}개 목록 ${expanded ? '접기' : '펼치기'}`}
                        onClick={() => setNarrowExpanded(!expanded)}>
                        {sidebar.length}개 <span aria-hidden="true">{expanded ? '⌃' : '⌄'}</span>
                    </button> : <span>{sidebar.length}개</span>}</div>
                <div id="smart-candidate-list" className={styles.candidateList} role="region" aria-label="대기 및 선점 목록" hidden={!expanded}>
                {sidebarGroups.map((items, groupIndex) => <div key={items[0].groupId} role="group" aria-label={`신청 묶음 ${groupIndex + 1}`}
                    className={styles.sidebarGroup}>
                    {items.map((candidate, index) => <button type="button" key={candidate.groupId}
                    aria-label={`${index + 1}번 ${titles[candidate.kind]} · ${getSeatLabel(candidate.zone)} · ${candidate.movieTitle} · ${candidate.theaterName} · ${time(candidate.startTime)}`}
                    className={styles.candidateCard} disabled={flow.busy} aria-pressed={selected?.groupId === candidate.groupId} onClick={() => {
                        flow.select(candidate.groupId);
                        if (narrow) { setNarrowExpanded(false); toggleList.current?.focus(); }
                    }}>
                    <strong className={styles.cardMovie}><MarqueeText text={candidate.movieTitle} /></strong>
                    <span className={`${styles.cardMeta} ${styles.cardTheater}`}><MarqueeText text={candidate.theaterName} /><span className={styles.cardScreen}>{candidate.screenName}</span></span>
                    <span className={`${styles.cardMeta} ${styles.cardSchedule}`}>{formatShowDate(candidate.startTime)} {formatShowTime(candidate.startTime)} → {formatShowTime(candidate.endTime)}</span>
                    <span className={styles.cardTop}>
                        <strong>{titles[candidate.kind]}</strong>
                        {candidate.payment?.reservation?.status === 'PENDING' && <CandidateDeadline reservation={candidate.payment.reservation} receivedAt={flow.receivedAt}/>}
                        {!candidate.payment?.reservation && <span className={styles.candidateStatus}>{candidate.waiting?.items?.[0]?.status === 'WAITING' ? `대기순서 ${candidate.waiting.items[0].aheadCount + 1}번` : '대기 일시정지'}</span>}
                    </span>
                </button>)}</div>)}
                {!sidebar.length && <p className={styles.sidebarNote}>진행 중인 스마트 대기·선점이 없습니다.</p>}
                </div>
            </aside>
            <section className={styles.candidateDetail} aria-label="선택한 후보 상세">
                {selected ? <>
                    {booking.params.get('tossResult') === 'success' && selected.payment?.reservation?.status !== 'CONFIRMED'
                        ? <div className={styles.notice} role="status">토스 결제 승인 결과와 예매 상태를 확인하고 있습니다…</div>
                        : selected.payment?.reservation ? <ReservationPanel key={selected.groupId} candidateLabel={titles[selected.kind]} flow={{ reservation: selected.payment.reservation,
                            payment: selected.payment, receivedAt: flow.receivedAt, busy: flow.busy, error: flow.error,
                            pay: fail => flow.mutate(selected, 'pay', fail), payToss: () => flow.payToss(selected),
                            cancel: () => flow.mutate(selected, 'cancel'), refresh: flow.refresh }} onRestart={back}/>
                            : <CandidateWaiting key={selected.groupId} candidate={selected} flow={flow}/>}
                </> : <div className={styles.notice}>현재 남아 있는 후보가 없습니다. 새 조건으로 다시 찾아주세요.</div>}
            </section>
        </div>}
    </div>;
}

function MarqueeText({ text }) {
    const viewport = useRef(null), content = useRef(null);
    useLayoutEffect(() => {
        const frame = viewport.current, label = content.current;
        let active = true;
        const measure = () => {
            if (!active) return;
            const distance = Math.max(0, label.scrollWidth - frame.clientWidth);
            frame.dataset.overflow = String(distance > 1);
            frame.style.setProperty('--marquee-distance', `${-distance}px`);
            frame.style.setProperty('--marquee-duration', `${Math.max(2.5, distance / 45 + 1)}s`);
        };
        measure();
        const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
        observer?.observe(frame); observer?.observe(label);
        window.addEventListener('resize', measure);
        document.fonts?.ready.then(measure);
        return () => { active = false; observer?.disconnect(); window.removeEventListener('resize', measure); };
    }, [text]);
    return <span ref={viewport} className={styles.marquee} title={text}><span ref={content}>{text}</span></span>;
}

function CandidateDeadline({ reservation, receivedAt }) {
    const { remaining } = useReservationClock(reservation, receivedAt);
    return <span className={styles.candidateStatus}>{remaining > 0 ? `선점 ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}` : '선점 만료'}</span>;
}

function CandidateWaiting({ candidate, flow }) {
    const [confirmCancel, setConfirmCancel] = useState(false);
    const panel = useRef(null), title = useRef(null);
    const queue = candidate.waiting?.items?.[0];
    const waiting = queue?.status === 'WAITING';
    useLayoutEffect(() => {
        if (!waiting || !title.current) return;
        const heading = title.current, section = panel.current;
        const text = heading.querySelector('span > span');
        let active = true;
        const resize = () => {
            if (!active || !text) return;
            const cardStyle = getComputedStyle(heading.parentElement);
            const padding = parseFloat(cardStyle.paddingLeft) + parseFloat(cardStyle.paddingRight)
                + parseFloat(cardStyle.borderLeftWidth) + parseFloat(cardStyle.borderRightWidth);
            section.style.setProperty('--waiting-width', `${Math.max(560, Math.ceil(text.scrollWidth + padding))}px`);
        };
        resize();
        const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null;
        if (text) observer?.observe(text);
        document.fonts?.ready.then(resize);
        window.addEventListener('resize', resize);
        return () => { active = false; observer?.disconnect(); window.removeEventListener('resize', resize); };
    }, [candidate.movieTitle, waiting]);
    return <section ref={panel} className={`${reservationStyles.reservation} ${styles.waitingReservation}`} aria-label="후보 구역 대기">
        <div className={reservationStyles.resultIcon} aria-hidden="true">{waiting ? '◷' : '—'}</div>
        <h2>{status(candidate)}</h2>
        {waiting ? <><div className={`${reservationStyles.clock} ${styles.waitingNumber}`}><span>대기순서</span><strong>{queue.aheadCount + 1}<small>번</small></strong></div>
            <div className={reservationStyles.ticket}>
                <h3 ref={title} className={styles.waitingTitle}><MarqueeText text={candidate.movieTitle} /></h3>
                <div className={`${reservationStyles.ticketMain} ${styles.waitingTicketMain}`}><div>
                    <div className={`${reservationStyles.movieInfo} ${styles.waitingMovieInfo}`}>
                        <p><InlineDetails items={[candidate.theaterName, candidate.screenName]} /></p>
                        <p><InlineDetails items={[formatShowDate(candidate.startTime), <>{formatShowTime(candidate.startTime)} → {formatShowTime(candidate.endTime)}</>]} /></p>
                    </div>
                    <div className={`${styles.waitingSeatInfo} ${styles.waitingSeatRow}`}>
                        <p className={styles.candidateKind}>{titles[candidate.kind]}</p>
                        <span className={styles.partySize}>{candidate.partySize}명</span>
                    </div>
                </div><SeatZoneMap zone={candidate.zone} className={styles.zoneMap}/></div>
            </div>
            </>
            : <p>다른 후보의 대기와 예매는 계속 유지됩니다.</p>}
        <div className={reservationStyles.paymentRow}>
            {waiting && <GlassButton className={reservationStyles.cancelButton} disabled={flow.busy} onClick={() => setConfirmCancel(true)}>대기 취소</GlassButton>}
            <button type="button" className={reservationStyles.refreshButton} aria-label="대기 상태 새로고침" title="새로고침" disabled={flow.busy} onClick={flow.refresh}>
                <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.1 8a8 8 0 1 0 .5 7"/></svg>
            </button>
        </div>
        {waiting && confirmCancel && <CancelDialog busy={flow.busy} onClose={() => setConfirmCancel(false)} titleId="waiting-cancel-title">
            <h2 id="waiting-cancel-title">대기를 취소할까요?</h2>
            <div className={reservationStyles.resultActions}>
                <GlassButton disabled={flow.busy} onClick={() => setConfirmCancel(false)}>유지하기</GlassButton>
                <button className={ui.primary} disabled={flow.busy} onClick={async () => {
                    await flow.mutate(candidate, 'cancel-waiting'); setConfirmCancel(false);
                }}>대기 취소 확정</button>
            </div>
        </CancelDialog>}
    </section>;
}
