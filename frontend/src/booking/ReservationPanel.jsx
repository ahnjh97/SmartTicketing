import { useEffect, useRef, useState } from 'react';
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
        <p className={styles.eyebrow}>{confirmed ? 'BOOKING COMPLETE' : pending ? 'YOUR SEATS ARE ON HOLD' : 'BOOKING STATUS'}</p>
        <h2 ref={heading} tabIndex={-1}>{labels[r.status] || r.status}</h2>
        {pending && <div className={styles.clock} data-urgent={remaining < 60}>
            <span>결제까지 남은 시간</span><strong role="timer" aria-label={`남은 시간 ${time}`}>{time}</strong>
            <div className={styles.timeTrack}><i style={{ width: `${Math.min(100, remaining / 3)}%` }} /></div>
            <small>서버 기준 · 실패하거나 새로고침해도 연장되지 않습니다</small>
        </div>}
        <div className={styles.ticket} data-confirmed={confirmed}>
            <div className={styles.ticketHeading}><span>SMART TICKETING</span><span>{r.status}</span></div>
            <h3>{r.movieTitle}</h3><p>{r.theaterName} · {r.screenName}</p>
            <p>{r.startTime.slice(0,10)} · {r.startTime.slice(11,16)} → {r.endTime.slice(0,10) !== r.startTime.slice(0,10) && '익일 '}{r.endTime.slice(11,16)}</p>
            <div className={styles.ticketSeats}><span>SEATS</span><strong>{r.seatLabels.join(' · ')}</strong></div>
            <div className={styles.total}><span>{r.status === 'CANCELLED' ? '예약 금액' : '서버 확정 금액'}</span><strong>{r.totalAmount.toLocaleString('ko-KR')}<small>원</small></strong></div>
            {ticket && <p className={styles.ticketNumber}>티켓 번호 <strong>{ticket.ticketNumber}</strong></p>}
        </div>
        {failed && pending && <p className={styles.error} role="alert">모의결제에 실패했습니다. 남은 시간 안에 다시 결제할 수 있습니다.</p>}
        {pending && remaining === 0 && <p role="status">결제 가능 시간이 지났습니다. 서버의 최신 상태를 확인해주세요.</p>}
        {pending && <>
            <p className={styles.hint}>실제 금액이 청구되지 않는 모의결제입니다.</p>
            <button className={`${ui.primary} ${styles.pay}`} disabled={busy || remaining === 0} onClick={() => pay(false)}>{busy ? '서버에서 처리 중…' : failed ? '모의결제 다시 시도' : `${r.totalAmount.toLocaleString('ko-KR')}원 모의결제`}</button>
            {import.meta.env.DEV && import.meta.env.VITE_BOOKING_MOCK_FAILURE === 'true' && <GlassButton disabled={busy || remaining === 0} onClick={() => pay(true)}>개발용 결제 실패 확인</GlassButton>}
        </>}
        {confirmed && <Link className={styles.ticketLink} to="/tickets">내 티켓에서 확인 →</Link>}
        {r.status === 'CANCELLED' && <p>예약 전체가 취소되었습니다. 결제 완료 건은 수수료 없이 모의 전액 환불됩니다.</p>}
        {r.status === 'EXPIRED' && <p>좌석을 다시 선택해주세요. 이전 선점은 연장되지 않습니다.</p>}
        <div className={styles.resultActions}><GlassButton disabled={busy} onClick={refresh}>최신 상태 확인</GlassButton>
            {cancellable && <GlassButton disabled={busy} onClick={() => setConfirmCancel(true)}>{pending ? '선점 취소' : '예매 전체 취소'}</GlassButton>}
            {!pending && !confirmed && <GlassButton onClick={onRestart}>다시 예매하기</GlassButton>}</div>
        {confirmCancel && cancellable && <div className={styles.cancelBox} role="group" aria-label="전체 취소 확인">
            <strong>{pending ? '선점한 모든 좌석을 해제할까요?' : '모든 좌석을 취소하고 모의 전액 환불할까요?'}</strong>
            <p>부분 취소는 지원하지 않으며, 취소한 좌석의 재확보는 보장되지 않습니다.</p>
            <div className={styles.resultActions}><GlassButton disabled={busy} onClick={() => setConfirmCancel(false)}>유지하기</GlassButton>
                <button className={ui.primary} disabled={busy} onClick={async () => { await cancel(); setConfirmCancel(false); }}>전체 취소 확정</button></div>
        </div>}
        <p className={styles.hint}>상영 시작 전까지 전체 취소 가능 · 부분 취소 불가</p>
    </section>;
}
