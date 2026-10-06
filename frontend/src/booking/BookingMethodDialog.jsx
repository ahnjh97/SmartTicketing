import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import styles from './BookingViews.module.css';
import AudiencePicker from './AudiencePicker.jsx';

export default function BookingMethodDialog({ booking, movie, show }) {
    const dialog = useRef(null);
    const { update, valid, smartReady, enter } = booking;
    const close = () => update({ movie: null, showtime: null });
    useEffect(() => {
        const previous = document.activeElement;
        const overflow = document.body.style.overflow;
        dialog.current.showModal();
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = overflow; previous?.focus(); };
    }, []);
    return createPortal(<dialog ref={dialog} className={styles.methodDialog} aria-labelledby="booking-method-title"
        onCancel={event => { event.preventDefault(); close(); }}
        onClick={event => {
            if (event.target !== dialog.current) return;
            const rect = dialog.current.getBoundingClientRect();
            if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) close();
        }}>
        <header className={styles.methodHeading}>
            <h2 id="booking-method-title">{movie.title}</h2>
            <button type="button" aria-label="예매 방식 선택 닫기" onClick={close}>×</button>
        </header>
        <p className={styles.methodSummary}><time dateTime={booking.date}>{booking.date.replaceAll('-', '.')}</time> · {show.screenName} · {show.startTime.slice(11, 16)} → {show.endTime.slice(11, 16)}</p>
        <div className={styles.methodAudience}><AudiencePicker booking={booking} /></div>
        <div className={styles.methodActions}>
            <button type="button" aria-label="일반예매" disabled={!valid} onClick={() => enter('THEATER_NORMAL')}>
                <strong>일반예매</strong>
            </button>
            <button type="button" aria-label="스마트예매" disabled={!valid || !smartReady} onClick={() => enter('THEATER_SMART')}>
                <strong>스마트예매</strong>
            </button>
        </div>
        {!show.availableSeats && <p className={styles.methodSummary}>매진된 회차입니다. 일반예매는 원하는 좌석을, 스마트예매는 추천 구역을 선택해 대기할 수 있습니다.</p>}
    </dialog>, document.body);
}
