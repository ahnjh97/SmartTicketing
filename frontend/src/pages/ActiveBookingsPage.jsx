import { Link } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import useActiveBookings from '../booking/useActiveBookings.js';
import useReservationClock from '../booking/useReservationClock.js';
import GlassButton from '../components/GlassButton.jsx';
import InlineDetails from '../components/InlineDetails.jsx';
import { PAGE_PATHS } from '../navigation.js';
import button from '../components/GlassButton.module.css';
import styles from './ActiveBookingsPage.module.css';

function showtime(value) {
    return new Date(value).toLocaleString('ko-KR', {
        timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric',
        hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    });
}

function BookingLink({ group, children }) {
    return <Link className={`${button.button} ${styles.action}`}
        to={`${PAGE_PATHS.bookingRestore}?group=${group.id}`}>{children}</Link>;
}

function HoldingCard({ group }) {
    const { reservation, receivedAt } = group;
    const { remaining } = useReservationClock(reservation, receivedAt);
    const time = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
    return <article className={styles.card}>
        <div className={styles.cardTop}>
            <h3>{group.movieTitle}</h3>
            <span className={styles.timer} data-urgent={remaining < 60} role="timer" aria-label={`결제까지 ${time}`}>
                {remaining ? time : '시간 만료'}
            </span>
        </div>
        <p className={styles.details}><InlineDetails items={[reservation.theaterName, reservation.screenName]} /></p>
        <p className={styles.details}>{showtime(reservation.startTime)}</p>
        <div className={styles.cardBottom}>
            <strong className={styles.seats}>{reservation.seatLabels.join(', ')}</strong>
            <BookingLink group={group}>{remaining ? '결제하기' : '상태 확인'}</BookingLink>
        </div>
    </article>;
}

function WaitingCard({ group }) {
    return <article className={styles.card}>
        <div className={styles.cardTop}><h3>{group.movieTitle}</h3><span className={styles.party}>{group.partySize}명</span></div>
        <ul className={styles.queues}>{group.queues.map(queue => <li key={queue.id}>
            <div><strong>{queue.theaterName}</strong>
                <p className={styles.details}><InlineDetails items={[queue.screenName, showtime(queue.startTime)]} /></p>
            </div>
            <span className={styles.ahead}>{queue.status === 'PAUSED' ? '일시정지' : `앞 ${queue.aheadCount}명`}</span>
        </li>)}</ul>
        <div className={styles.cardBottom}><BookingLink group={group}>대기 확인</BookingLink></div>
    </article>;
}

export default function ActiveBookingsPage() {
    const { user } = useAuth();
    const { items, error, refreshing, retry } = useActiveBookings(user?.id);
    const holdings = items?.filter(item => item.kind === 'holding') ?? [];
    const waiting = items?.filter(item => item.kind === 'waiting') ?? [];
    return <section className={styles.page}>
        <div className={styles.header}><h1>내 대기 및 선점</h1>
            <GlassButton onClick={retry} disabled={refreshing} aria-label="대기 및 선점 새로고침">새로고침</GlassButton>
        </div>
        {error && <p className={styles.feedback} role="alert">대기 및 선점 정보를 갱신하지 못했습니다. 새로고침해 주세요.</p>}
        {!items && !error && <p className={styles.feedback} role="status">불러오는 중…</p>}
        {items?.length === 0 && !error && <div className={styles.empty}>
            <p>대기 중이거나 선점한 좌석이 없습니다.</p>
            <Link className={button.button} to={PAGE_PATHS.home}>영화 보러가기</Link>
        </div>}
        {holdings.length > 0 && <section className={styles.section} aria-labelledby="holding-heading">
            <h2 id="holding-heading">선점 중 <span>{holdings.length}</span></h2>
            <div className={styles.grid}>{holdings.map(group => <HoldingCard key={group.id} group={group} />)}</div>
        </section>}
        {waiting.length > 0 && <section className={styles.section} aria-labelledby="waiting-heading">
            <h2 id="waiting-heading">대기 중 <span>{waiting.length}</span></h2>
            <div className={styles.grid}>{waiting.map(group => <WaitingCard key={group.id} group={group} />)}</div>
        </section>}
    </section>;
}
