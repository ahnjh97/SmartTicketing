import { formatShowtime as showtime, formatShowTime } from '../utils/showtimeFormat.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import BookingHistory from '../booking/BookingHistory.jsx';
import { Link } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import useActiveBookings from '../booking/useActiveBookings.js';
import useReservationClock from '../booking/useReservationClock.js';
import GlassButton from '../components/GlassButton.jsx';
import InlineDetails from '../components/InlineDetails.jsx';
import { PAGE_PATHS } from '../navigation.js';
import styles from './ActiveBookingsPage.module.css';



function HoldingCard({ group }) {
    const { reservation, receivedAt } = group;
    const { remaining } = useReservationClock(reservation, receivedAt);
    const time = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
    return <Link className={`${styles.card} ${styles.cardLink}`}
        to={`${PAGE_PATHS.bookingRestore}?group=${group.id}`}
        aria-label={`${group.movieTitle} ${reservation.seatLabels.join(', ')} 예매 확인`}>
        <div className={styles.cardTop}>
            <h3>{group.movieTitle}</h3>
            <BookingStatus held />
        </div>
        <p className={styles.details}><InlineDetails items={[reservation.theaterName, reservation.screenName]} /></p>
        <p className={styles.details}>{showtime(reservation.startTime)}{reservation.endTime && <> → {formatShowTime(reservation.endTime)}</>}</p>
        <div className={styles.cardBottom}>
            <div className={styles.seatInfo}>
                <p className={styles.candidateKind}>{group.candidateKind ? ({ FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' }[group.candidateKind]) : '일반 예매'}</p>
                <strong className={styles.seats}>{reservation.seatLabels.join(', ')}</strong>
            </div>
            <div className={styles.countdown}>
                <span className={styles.timerLabel}>결제까지 남은 시간</span>
                <strong className={styles.timer} data-urgent={remaining < 60} role="timer" aria-label={`결제까지 ${time}`}>
                    {remaining ? time : '시간 만료'}
                </strong>
            </div>
        </div>
    </Link>;
}

function WaitingCard({ group }) {
    const smart = group.entryPoint !== 'THEATER_NORMAL';
    return <Link className={`${styles.card} ${styles.cardLink}`}
        to={`${PAGE_PATHS.bookingRestore}?group=${group.id}`} aria-label={`${group.movieTitle} ${smart ? '스마트예매 ' : ''}대기 확인`}>
        <div className={styles.cardTop}><h3>{group.movieTitle}</h3><BookingStatus /></div>
        {group.queues.map(queue => <div className={styles.waitingDetails} key={queue.id}>
            <p className={styles.details}><InlineDetails items={[queue.theaterName, queue.screenName]} /></p>
            <p className={styles.details}>{showtime(queue.startTime)}{queue.endTime && <> → {formatShowTime(queue.endTime)}</>}</p>
            <div className={styles.cardBottom}>
                <div className={styles.seatInfo}>
                    <p className={styles.candidateKind}>{group.candidateKind ? ({ FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' }[group.candidateKind]) : smart ? '스마트예매' : '일반 예매'}</p>
                    <div className={styles.seatDescription}><strong className={styles.seats}>{queue.seatLabels?.length ? queue.seatLabels.join(', ') : getSeatLabel(queue.seatZone || group.zone)}</strong><span>{group.partySize}명</span></div>
                </div>
                <span className={styles.ahead} aria-label={queue.status === 'PAUSED' ? '일시정지' : `대기순서 ${queue.aheadCount + 1}번`}>
                    <span>대기순서</span>
                    {queue.status === 'PAUSED' ? '일시정지' : <span className={styles.orderValue}>
                    <strong className={styles.orderNumber}>{queue.aheadCount + 1}</strong><small>번</small>
                    </span>}
                </span>
            </div>
        </div>)}
    </Link>;
}

function BookingStatus({ held = false }) {
    return <span className={styles.bookingStatus} data-held={held}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            {held ? <path d="m8 12 3 3 5-6" /> : <path d="M12 7v5l3 2" />}
        </svg>
        {held ? '선점 완료' : '대기중'}
    </span>;
}

export default function ActiveBookingsPage() {
    const { user } = useAuth();
    const [searchParams, setSearchParams] = useSearchParams();
    const [history, setHistory] = useState(searchParams.get('history') === '1');
    const { items, error } = useActiveBookings(user?.id);
    const smart = (items || []).filter(item => item.entryPoint !== 'THEATER_NORMAL');
    const manual = (items || []).filter(item => item.entryPoint === 'THEATER_NORMAL');
    return <section className={styles.page}>
        <div className={styles.header}><h1>내 대기 및 선점</h1>
            <GlassButton onClick={() => {
                setHistory(value => {
                    const next = !value;
                    setSearchParams(next ? { history: '1' } : {}, { replace: true });
                    return next;
                });
            }} aria-pressed={history}>{history ? '진행 중 보기' : '지난 내역'}</GlassButton>
        </div>
        {history ? <BookingHistory key={user?.id}/> : <>
        {error && <p className={styles.feedback} role="alert">대기 및 선점 정보를 갱신하지 못했습니다. 페이지를 새로고침해 주세요.</p>}
        {!items && !error && <p className={styles.feedback} role="status">불러오는 중…</p>}
        <section className={styles.section} aria-labelledby="smart-heading">
            <h2 id="smart-heading">스마트 예매 <span>{smart.length}개</span></h2>
            <div className={styles.grid}>{smart.map(group => group.kind === 'holding'
                ? <HoldingCard key={group.id} group={group}/> : <WaitingCard key={group.id} group={group}/>)}</div>
            {!smart.length && <p className={styles.feedback}>진행 중인 스마트예매가 없습니다.</p>}
        </section>
        <section className={styles.section} aria-labelledby="manual-heading">
            <h2 id="manual-heading">일반 예매 <span>{manual.length}건</span></h2>
            <div className={styles.grid}>{manual.map(group => group.kind === 'holding'
                ? <HoldingCard key={group.id} group={group}/> : <WaitingCard key={group.id} group={group}/>)}</div>
            {!manual.length && <p className={styles.feedback}>진행 중인 일반예매가 없습니다.</p>}
        </section>
        </>}
    </section>;
}
