import styles from './BookingViews.module.css';

export default function AudiencePicker({ booking }) {
    const { adultCount, youthCount, update } = booking;
    const total = adultCount + youthCount;
    const change = (field, value) => update({ adult: adultCount, youth: youthCount, [field]: value,
        party: total + value - (field === 'adult' ? adultCount : youthCount), eligible: null, guardian: null });
    return <fieldset className={styles.audiencePicker}>
        <legend>관람 인원 <span>최대 6명</span></legend>
        <div className={styles.audienceRow}>{[['adult', '성인', adultCount], ['youth', '청소년', youthCount]].map(([field, label, value]) =>
            <div className={styles.counter} key={field}><span>{label}</span><div className={styles.counterControls}>
                <button type="button" aria-label={`${label} 인원 줄이기`} disabled={value <= 0 || total <= 1} onClick={() => change(field, value - 1)}>−</button>
                <output aria-label={`${label} 인원`} aria-live="polite">{value}</output>
                <button type="button" aria-label={`${label} 인원 늘리기`} disabled={booking.authLoading || total >= 6 || (field === 'adult' && booking.youthMember)} onClick={() => change(field, value + 1)}>+</button>
            </div></div>)}
            <div className={styles.partyTotal}><span>총인원</span><output aria-label="총인원" aria-live="polite"><strong>{total}</strong>명</output></div>
        </div>
    </fieldset>;
}
