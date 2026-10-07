import { formatShowtime } from '../utils/showtimeFormat.js';
import { useEffect, useState } from 'react';
import { bookingApi } from '../api/booking.js';
import { getSeatLabel } from '../utils/seatLabels.js';
import GlassButton from '../components/GlassButton.jsx';
import InlineDetails from '../components/InlineDetails.jsx';
import styles from '../pages/ActiveBookingsPage.module.css';

export default function BookingHistory() {
    const [before, setBefore] = useState(null);
    const [revision, setRevision] = useState(0);
    const [state, setState] = useState({ items: [], nextBefore: null });
    useEffect(() => {
        const controller = new AbortController();
        let active = true;
        bookingApi.history(before, controller.signal).then(page => {
            if (active) setState(previous => ({ items: before ? [...previous.items, ...page.items] : page.items,
                nextBefore: page.nextBefore, loadedBefore: before, revision }));
        }).catch(error => {
            if (active) setState(previous => ({ ...previous, error, loadedBefore: before, revision }));
        });
        return () => { active = false; controller.abort(); };
    }, [before, revision]);
    const loading = state.loadedBefore !== before || state.revision !== revision;
    return <section aria-label="지난 대기 및 선점 내역">
        <div className={styles.grid}>{state.items.map(item => <article className={styles.card} key={item.id}>
            <div className={styles.cardTop}><h3>{item.movieTitle}</h3><span className={styles.historyStatus}>
                {item.kind === 'holding' ? '선점' : '대기'} {item.status === 'CANCELLED' ? '취소' : '만료'}
            </span></div>
            <p className={styles.details}><InlineDetails items={[item.entryPoint === 'THEATER_NORMAL' ? '일반 예매' : '스마트 예매', getSeatLabel(item.zone), `${item.partySize}명`].filter(Boolean)}/></p>
            <p className={styles.details}><InlineDetails items={[item.theaterName, item.screenName]}/></p>
            <p className={styles.details}>{formatShowtime(item.startTime)}</p>
        </article>)}</div>
        {loading && <p className={styles.feedback} role="status">불러오는 중…</p>}
        {!loading && state.error && <div className={styles.historyFooter} role="alert">지난 내역을 불러오지 못했습니다.
            <GlassButton onClick={() => setRevision(value => value + 1)}>다시 시도</GlassButton>
        </div>}
        {!loading && !state.error && !state.items.length && <p className={styles.feedback}>취소·만료된 내역이 없습니다.</p>}
        {!loading && !state.error && state.nextBefore != null && <div className={styles.historyFooter}>
            <GlassButton onClick={() => setBefore(state.nextBefore)}>더 보기</GlassButton>
        </div>}
    </section>;
}
