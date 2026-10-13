import { useRef, useState } from 'react';
import styles from './DatePicker.module.css';

const iso = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

export default function DatePicker({ id, value, onChange, min = '1900-01-01', max = iso(new Date()), required, disabled }) {
    const [open, setOpen] = useState(false);
    const [month, setMonth] = useState(() => (value || max).slice(0, 7));
    const trigger = useRef(null);
    const [year, number] = month.split('-').map(Number);
    const count = new Date(year, number, 0).getDate();
    const offset = new Date(year, number - 1, 1).getDay();
    const close = () => { setOpen(false); trigger.current?.focus(); };
    const choose = date => { onChange({ target: { value: date } }); close(); };
    function move(delta) { setMonth(iso(new Date(year, number - 1 + delta, 1)).slice(0, 7)); }
    return <div className={styles.root} onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }} onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); close(); } }}>
        <div className={styles.field}>
            <input id={id} type="date" value={value} onChange={onChange} min={min} max={max} required={required} disabled={disabled} />
            <button ref={trigger} type="button" className={styles.trigger} aria-label="생년월일 달력 열기" aria-expanded={open} aria-controls={`${id}-calendar`} disabled={disabled}
                onClick={() => { setMonth((value || max).slice(0, 7)); setOpen(!open); }}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4m10-4v4M3 11h18M7 15h2m6 0h2m-10 3h2"/></svg>
            </button>
        </div>
        {open && <section id={`${id}-calendar`} className={styles.calendar} aria-label="생년월일 달력">
            <div className={styles.heading}>
                <button type="button" aria-label="이전 달" disabled={month <= min.slice(0, 7)} onClick={() => move(-1)}>‹</button>
                <select aria-label="연도" value={year} onChange={e => setMonth(`${e.target.value}-${String(Math.min(number, Number(e.target.value) === Number(max.slice(0, 4)) ? Number(max.slice(5, 7)) : 12)).padStart(2, '0')}`)}>
                    {Array.from({ length: Number(max.slice(0, 4)) - Number(min.slice(0, 4)) + 1 }, (_, i) => Number(max.slice(0, 4)) - i).map(y => <option key={y} value={y}>{y}년</option>)}
                </select>
                <select aria-label="월" value={number} onChange={e => setMonth(`${year}-${e.target.value.padStart(2, '0')}`)}>
                    {Array.from({ length: 12 }, (_, i) => i + 1).map(m => <option key={m} value={m} disabled={`${year}-${String(m).padStart(2, '0')}` < min.slice(0, 7) || `${year}-${String(m).padStart(2, '0')}` > max.slice(0, 7)}>{m}월</option>)}
                </select>
                <button type="button" aria-label="다음 달" disabled={month >= max.slice(0, 7)} onClick={() => move(1)}>›</button>
            </div>
            <div className={styles.days}>
                {['일', '월', '화', '수', '목', '금', '토'].map(day => <span key={day} className={styles.weekday}>{day}</span>)}
                {Array.from({ length: offset }, (_, i) => <span key={`blank-${i}`} />)}
                {Array.from({ length: count }, (_, i) => {
                    const date = `${month}-${String(i + 1).padStart(2, '0')}`;
                    return <button key={date} type="button" aria-label={`${year}년 ${number}월 ${i + 1}일`} aria-pressed={date === value} disabled={date < min || date > max}
                        onClick={() => choose(date)}>{i + 1}</button>;
                })}
            </div>
            <p className={styles.hint}>연도와 월을 선택한 후 날짜를 골라주세요.</p>
        </section>}
    </div>;
}
