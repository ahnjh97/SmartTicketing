import InlineDetails from '../components/InlineDetails.jsx';
import { useEffect, useRef, useState } from 'react';
import GlassButton from '../components/GlassButton.jsx';
import useWaitingQueues from './useWaitingQueues.js';
import styles from './WaitingPanel.module.css';
import ui from './BookingComponents.module.css';
import { getSeatLabel } from '../utils/seatLabels.js';

const labels = { WAITING: '배정 대기', PAUSED: '다른 회차 선점으로 일시정지', HOLDING: '좌석 확보 후 결제 대기', COMPLETED: '결제 완료', EXPIRED: '기회 종료', CANCELLED: '취소됨' };
const time = value => `${value.slice(5,10).replace('-', '/')} ${value.slice(11,16)}`;

export default function WaitingPanel({ flow }) {
    const queue = useWaitingQueues(flow.user?.id, flow.group?.id);
    const [selected, setSelected] = useState([]);
    const [confirmCancel, setConfirmCancel] = useState(false);
    const heading = useRef(null);
    const refresh = useRef(flow.refresh);
    useEffect(() => { refresh.current = flow.refresh; }, [flow.refresh]);
    const active = queue.data?.activeReservationId;
    useEffect(() => { if (active && String(active) !== String(flow.reservation?.id)) refresh.current(); }, [active, flow.reservation?.id]);
    useEffect(() => { if (flow.group?.status && queue.data?.groupStatus && queue.data.groupStatus !== flow.group.status) refresh.current(); }, [queue.data?.groupStatus, flow.group?.status]);
    useEffect(() => { if (queue.error) heading.current?.focus(); }, [queue.error]);
    const data = queue.data;
    const selectedIds = selected.filter(id => data?.choices.some(choice => choice.showtimeId === id));
    const busy = queue.busy || flow.busy;
    if (flow.reservation && data && !data.items.length && !queue.error) return null;
    return <section className={styles.panel} aria-label="복수 회차 대기" aria-busy={queue.busy}>
        <header className={styles.heading}><div><p className={styles.eyebrow}>MORE POSSIBILITIES</p><h3>기다리는 동안, 기회는 넓게.</h3>
            <p>회차마다 한 구역에 대기합니다. 구역을 지정하지 않으면 첫 번째 선호 구역으로 신청됩니다. 한 곳이 확보되면 나머지는 잠시 멈춥니다.</p></div><span className={styles.badge}>WAITLIST</span></header>
        
        {queue.error && <div className={styles.error} role="alert"><h4 ref={heading} tabIndex={-1}>대기 상태를 확인해주세요</h4><p>{queue.error.message}</p><GlassButton onClick={queue.retry} disabled={busy}>다시 확인</GlassButton></div>}
        {!data && !queue.error && <p role="status">대기 가능한 회차를 확인하고 있습니다…</p>}
        {data && <>
            <div className={styles.rules}><span><b>01</b> 연석 우선, 허용 조합으로 분할</span><span><b>02</b> 선점 후 5분 결제</span><span><b>03</b> 만료 시 다른 회차 재개</span></div>
            {data.items.length > 0 && <ul className={styles.queues} aria-label="신청한 회차">{data.items.map(item => <li key={item.id} className={styles.card} data-state={item.status}>
                <div className={styles.number}><span>{item.seatZone ? `${getSeatLabel(item.seatZone)} 발급 번호` : '발급 번호'}</span><strong>{String(item.queueNumber).padStart(2,'0')}</strong></div>
                <div className={styles.details}><h4><InlineDetails items={[item.theaterName, item.screenName]} /></h4><p>{time(item.startTime)}</p><strong className={styles.status}>{labels[item.status] || item.status}</strong>
                    {['WAITING','PAUSED'].includes(item.status) && <p>앞에서 배정 대기 중 <b>{item.aheadCount}건</b>. 조건 충족 시 순서대로 배정</p>}
                    {item.status === 'PAUSED' && <p>발급 번호는 유지됩니다. 다른 사용자의 배정은 계속됩니다.</p>}
                    {item.status === 'HOLDING' && <p>현재 예매의 결제 패널에서 남은 시간을 확인하세요.</p>}
                    {item.status === 'EXPIRED' && <p>이 그룹에서는 재신청되지 않습니다.</p>}</div>
            </li>)}</ul>}
            {data.groupStatus === 'ACTIVE' && <>
                <fieldset className={styles.choices} disabled={busy}><legend>대기할 회차 선택</legend>
                    {data.choices.length ? data.choices.map(choice => <label key={choice.showtimeId} className={styles.choice} data-selected={selectedIds.includes(choice.showtimeId)}>
                        <input type="checkbox" checked={selectedIds.includes(choice.showtimeId)} onChange={event => setSelected(previous => event.target.checked ? [...previous, choice.showtimeId] : previous.filter(id => id !== choice.showtimeId))}/>
                        <span><strong><InlineDetails items={[choice.theaterName, choice.screenName]} /></strong><small>{time(choice.startTime)} → {time(choice.endTime)}</small></span><span aria-hidden="true">＋</span>
                    </label>) : <p>추가로 신청할 수 있는 회차가 없습니다.</p>}
                </fieldset>
                {data.choices.length > 0 && <button className={`${ui.primary} ${styles.submit}`} disabled={busy || selectedIds.length === 0}
                    onClick={() => queue.register(selectedIds)}>{queue.busy ? '서버에서 처리 중…' : `선택한 ${selectedIds.length}개 회차에 대기 신청`}</button>}
                {!data.items.length && <p className={styles.note}>아직 등록된 대기가 없습니다. 신청 버튼을 눌러야 번호가 발급됩니다.</p>}
            </>}
            <p className={styles.note}>발급 번호와 앞선 신청 수는 다릅니다. 앞 신청의 전원 좌석 조건이 맞지 않으면 뒤 신청이 먼저 배정될 수 있습니다. 지나간 기회는 소급 보장하지 않습니다.</p>
            {['ACTIVE','HOLDING'].includes(data.groupStatus) && data.items.length > 0 && <div className={styles.cancel}>
                {confirmCancel ? <><p>이 그룹의 모든 대기와 미결제 선점을 취소합니다.</p><GlassButton disabled={busy} onClick={() => queue.cancel()}>전체 대기와 선점 취소 확정</GlassButton><GlassButton disabled={busy} onClick={() => setConfirmCancel(false)}>계속 대기</GlassButton></>
                    : <GlassButton disabled={busy} onClick={() => setConfirmCancel(true)}>이 그룹 전체 취소</GlassButton>}
            </div>}
            {data.groupStatus === 'CANCELLED' && <p role="status" className={styles.note}>이 관람 요청은 취소되었습니다.</p>}
        </>}
    </section>;
}
