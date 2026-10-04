import InlineDetails from '../components/InlineDetails.jsx';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import GlassButton from '../components/GlassButton.jsx';
import useReservationClock from './useReservationClock.js';
import styles from './ManualBooking.module.css';
import ui from './BookingComponents.module.css';

const labels = { PENDING: '좌석을 선점했습니다', CONFIRMED: '예매가 완료되었습니다', EXPIRED: '선점 시간이 만료되었습니다', CANCELLED: '예매가 취소되었습니다' };
export default function ReservationPanel({ flow, onRestart }) {
    const { reservation: r, payment, receivedAt, busy, pay, cancel, refresh } = flow;
    const { remaining, serverNow } = useReservationClock(r, receivedAt);
    const [confirmCancel, setConfirmCancel] = useState(false);
    const heading = useRef(null);
    useEffect(() => { heading.current?.focus(); }, [r.status]);
    const pending = r.status === 'PENDING';
    const confirmed = r.status === 'CONFIRMED';
    const ticket = payment?.ticket;
    const failed = payment?.status === 'FAILED';
    const time = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;
    const cancellable = (pending || confirmed) && Date.parse(r.startTime) > serverNow;
    return <section className={styles.reservation} aria-label="예약 및 결제">
        <div className={styles.resultIcon} data-success={confirmed} aria-hidden="true">{confirmed ? '✓' : pending ? '◷' : '—'}</div>
        <h2 ref={heading} tabIndex={-1}>{labels[r.status] || r.status}</h2>
        {pending && <div className={styles.clock} data-urgent={remaining < 60}>
                <span>결제까지 남은 시간</span><strong role="timer" aria-label={`남은 시간 ${time}`}>{time}</strong>
                <div className={styles.timeTrack}><i style={{ width: `${Math.min(100, remaining / 3)}%` }} /></div>
        </div>}
        <div className={styles.ticket} data-confirmed={confirmed}>
            <div className={styles.ticketMain}>
                <div><h3>{r.movieTitle}</h3><p><InlineDetails items={[r.theaterName, r.screenName]} /></p>
                    <p><InlineDetails items={[r.startTime.slice(0,10), <>{r.startTime.slice(11,16)} → {r.endTime.slice(11,16)}</>]} /></p>
                    <div className={styles.ticketSeats}><span>SEATS</span><strong>{r.seatLabels.join(', ')}</strong></div>
                    {ticket && <p className={styles.ticketNumber}>티켓 번호 <strong>{ticket.ticketNumber}</strong></p>}
                </div>
                <div className={styles.paymentCheckout}>
                    <div className={styles.total} aria-label="예약 금액"><strong>{r.totalAmount.toLocaleString('ko-KR')}<small>원</small></strong></div>
                    {pending && <button className={`${ui.primary} ${styles.pay}`} disabled={busy || remaining === 0} onClick={() => pay(false)}>{busy ? '처리 중…' : '모의결제'}</button>}
                    {confirmed && <Link className={`${ui.primary} ${styles.pay} ${styles.ticketLink}`} to="/tickets">내 티켓에서 확인</Link>}
                </div>
            </div>
        </div>
        <div className={styles.paymentRow}>
            {cancellable && <GlassButton className={styles.cancelButton} disabled={busy} onClick={() => setConfirmCancel(true)}>{pending ? '선점 취소' : '예매 전체 취소'}</GlassButton>}
            <button type="button" className={styles.refreshButton} aria-label="예약 상태 새로고침" title="새로고침" disabled={busy} onClick={refresh}>
                <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M19.1 8a8 8 0 1 0 .5 7"/></svg>
            </button>
        </div>
        {failed && pending && <p className={styles.error} role="alert">모의결제에 실패했습니다. 남은 시간 안에 다시 결제할 수 있습니다.</p>}
        {pending && remaining === 0 && <p role="status">결제 가능 시간이 지났습니다. 서버의 최신 상태를 확인해주세요.</p>}
        {pending && <>
            {import.meta.env.DEV && import.meta.env.VITE_BOOKING_MOCK_FAILURE === 'true' && <GlassButton disabled={busy || remaining === 0} onClick={() => pay(true)}>개발용 결제 실패 확인</GlassButton>}
        </>}
        {r.status === 'CANCELLED' && <p>예약 전체가 취소되었습니다. 결제 완료 건은 수수료 없이 모의 전액 환불됩니다.</p>}
        {r.status === 'EXPIRED' && <p>좌석을 다시 선택해주세요. 이전 선점은 연장되지 않습니다.</p>}
        {!pending && !confirmed && <div className={styles.resultActions}><GlassButton onClick={onRestart}>다시 예매하기</GlassButton></div>}
        {confirmCancel && cancellable && <CancelDialog busy={busy} onClose={() => setConfirmCancel(false)}>
            <h2 id="reservation-cancel-title">{pending ? '선점을 취소할까요?' : '예매를 취소할까요?'}</h2>
            <p>{pending ? '선점한 모든 좌석이 해제됩니다.' : '모든 좌석이 취소되고 결제 금액은 모의 전액 환불됩니다.'}</p>
            <p>부분 취소는 지원하지 않으며, 취소한 좌석의 재확보는 보장되지 않습니다.</p>
            <div className={styles.resultActions}><GlassButton disabled={busy} onClick={() => setConfirmCancel(false)}>유지하기</GlassButton>
                <button className={ui.primary} disabled={busy} onClick={async () => { await cancel(); setConfirmCancel(false); }}>전체 취소 확정</button></div>
        </CancelDialog>}
    </section>;
}

function CancelDialog({ busy, onClose, children }) {
    const dialog = useRef(null);
    useEffect(() => {
        const previous = document.activeElement;
        const overflow = document.body.style.overflow;
        const element = dialog.current;
        element.showModal();
        document.body.style.overflow = 'hidden';
        return () => {
            element.close();
            document.body.style.overflow = overflow;
            if (previous?.isConnected && !previous.disabled) previous.focus();
        };
    }, []);
    return createPortal(<dialog ref={dialog} className={`${ui.surface} ${styles.cancelDialog}`}
        aria-labelledby="reservation-cancel-title" aria-busy={busy}
        onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
        {children}
    </dialog>, document.body);
}
