import { formatShowtime as showtime } from '../utils/showtimeFormat.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import { useState } from 'react';
import BookingHistory from '../booking/BookingHistory.jsx';
import { Link } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import useActiveBookings from '../booking/useActiveBookings.js';
import useReservationClock from '../booking/useReservationClock.js';
import GlassButton from '../components/GlassButton.jsx';
import InlineDetails from '../components/InlineDetails.jsx';
import { PAGE_PATHS } from '../navigation.js';
import button from '../components/GlassButton.module.css';
import styles from './ActiveBookingsPage.module.css';



function BookingLink({ group, children }) {
    return <Link className={`${button.button} ${styles.action}`}
        to={`${PAGE_PATHS.bookingRestore}?group=${group.id}`}>{children}</Link>;
}

function HoldingCard({ group }) {
    const { reservation, receivedAt } = group;
    const { remaining } = useReservationClock(reservation, receivedAt);
    const time = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
    return <Link className={`${styles.card} ${styles.cardLink}`}
        to={`${PAGE_PATHS.bookingRestore}?group=${group.id}`}
        aria-label={`${group.movieTitle} ${reservation.seatLabels.join(', ')} 예매 확인`}>
        <div className={styles.cardTop}>
            <h3>{group.movieTitle}</h3>
        </div>
        <p className={styles.details}>{group.candidateKind ? ({ FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' }[group.candidateKind]) : '일반 예매'} · {getSeatLabel(group.zone)}</p>
        <p className={styles.details}><InlineDetails items={[reservation.theaterName, reservation.screenName]} /></p>
        <p className={styles.details}>{showtime(reservation.startTime)}</p>
        <div className={styles.cardBottom}>
            <strong className={styles.seats}>{reservation.seatLabels.join(', ')}</strong>
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
    return <article className={styles.card}>
        <div className={styles.cardTop}><h3>{group.movieTitle}</h3><span className={styles.party}>{group.partySize}명</span></div>
        {group.candidateKind && <p className={styles.details}>{{ FAST: '빠른 예매', BALANCED: '균형 추천', PREFERRED: '선호 좌석', DIRECT: '스마트예매' }[group.candidateKind]}</p>}
        <ul className={styles.queues}>{group.queues.map(queue => <li key={queue.id}>
            <div><strong>{queue.theaterName} · {queue.seatLabels?.length ? queue.seatLabels.join(', ') : getSeatLabel(queue.seatZone)}</strong>
                <p className={styles.details}><InlineDetails items={[queue.screenName, showtime(queue.startTime)]} /></p>
            </div>
            <span className={styles.ahead}>{queue.status === 'PAUSED' ? '일시정지' : `순번 ${queue.aheadCount + 1}번`}</span>
        </li>)}</ul>
        <div className={styles.cardBottom}><BookingLink group={group}>{group.entryPoint === 'THEATER_NORMAL' ? '좌석 확인 · 대기 변경' : '스마트예매에서 확인'}</BookingLink></div>
    </article>;
}

export default function ActiveBookingsPage() {
    const { user } = useAuth();
    const [history, setHistory] = useState(false);
    const { items, error } = useActiveBookings(user?.id);
    const smart = (items || []).filter(item => item.entryPoint !== 'THEATER_NORMAL');
    const manual = (items || []).filter(item => item.entryPoint === 'THEATER_NORMAL');
    return <section className={styles.page}>
        <div className={styles.header}><h1>내 대기 및 선점</h1>
            <GlassButton onClick={() => setHistory(value => !value)} aria-pressed={history}>{history ? '진행 중 보기' : '지난 내역'}</GlassButton>
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
