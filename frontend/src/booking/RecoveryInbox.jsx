import { useState } from 'react';
import { Link } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import GlassButton from '../components/GlassButton.jsx';
import { notificationApi } from '../api/notifications.js';
import useRecoveryInbox from './useRecoveryInbox.js';
import styles from './RecoveryInbox.module.css';

const labels = { ACTIVE: '조건·대기 확인', HOLDING: '선점 상태 확인', COMPLETED: '결제 완료', CANCELLED: '취소됨', EXPIRED: '종료됨' };
export default function RecoveryInbox() {
    const { user } = useAuth();
    const [pages, setPages] = useState([]);
    const { result, retry } = useRecoveryInbox(user?.id, pages.at(-1));
    const groups = result?.groups;
    const notifications = result?.notifications;
    const items = groups?.value?.items || [];
    return <section className={styles.inbox} aria-labelledby="recovery-title">
        <div className={styles.heading}><div><p className={styles.eyebrow}>YOUR NEXT MOVIE</p><h2 id="recovery-title">예약·대기 이어보기</h2>
            <p>다시 로그인해도, 창을 닫아도. 저장된 요청에서 이어가세요.</p></div><GlassButton onClick={retry}>새로고침</GlassButton></div>
        {!result && <p className={styles.loading} role="status">저장된 예매와 알림을 확인하고 있습니다…</p>}
        {groups?.status === 'rejected' && <p role="alert">예매 목록을 불러오지 못했습니다. {groups.reason.message}</p>}
        {groups?.status === 'fulfilled' && <>
            <div className={styles.grid}>{items.map(group => <article className={styles.card} key={group.id} data-holding={group.status === 'HOLDING'}>
                <span className={styles.badge}>{labels[group.status] || group.status}</span><h3>{group.movieTitle}</h3>
                <p>{group.viewingDate} · {group.partySize}명</p><Link className={styles.action} to={`/booking/restore?group=${group.id}`}>예약·대기 확인 <span aria-hidden="true">↗</span></Link>
            </article>)}</div>
            {!items.length && <p className={styles.empty}>저장된 예매 요청이 없습니다. 영화와 회차를 선택해 시작해보세요.</p>}
            <nav className={styles.paging} aria-label="예매 목록 페이지"><GlassButton disabled={!pages.length} onClick={() => setPages(p => p.slice(0, -1))}>이전</GlassButton>
                <GlassButton disabled={!groups.value.hasMore} onClick={() => setPages(p => [...p, items.at(-1).id])}>다음</GlassButton></nav>
            <p className={styles.hint}>목록은 최근 저장 상태입니다. 예약을 열면 서버에서 남은 시간과 현재 대기를 다시 확인합니다.</p>
        </>}
        <details className={styles.notifications} open><summary>예매 알림</summary>
            {notifications?.status === 'rejected' && <p role="alert">알림을 불러오지 못했습니다. {notifications.reason.message}</p>}
            {notifications?.status === 'fulfilled' && (!notifications.value.length ? <p>아직 알림이 없습니다.</p> : <ul>{notifications.value.map(n => <li key={n.id} data-unread={!n.read}>
                <div><span className={styles.badge}>{n.read ? '읽음' : '새 알림'}</span><p>{n.message}</p></div>
                {n.groupId && <Link className={styles.action} to={`/booking/restore?group=${n.groupId}`} onClick={() => {
                    // Read acknowledgement must never prevent recovery navigation.
                    notificationApi.read(n.id).catch(() => {});
                }}>예약·대기로 이동 ↗</Link>}
            </li>)}</ul>)}
        </details>
    </section>;
}
