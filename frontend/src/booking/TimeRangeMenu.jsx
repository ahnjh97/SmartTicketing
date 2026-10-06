import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { timeLabel, minuteOf, timeOptions } from './timeRange.js';
import GlassButton from '../components/GlassButton.jsx';
import styles from './TimeRangeMenu.module.css';


function TimeParts({ field, label, value, options, disabled, onChange }) {
    const selected = options.find(slot => slot.time === value);
    const hour = selected ? Math.floor(selected.minute / 60) : '';
    const hours = [...new Set(options.map(slot => Math.floor(slot.minute / 60)))];
    const minutes = options.filter(slot => Math.floor(slot.minute / 60) === hour);
    const previous = selected && options.find(slot => slot.minute === selected.minute - 60);
    const next = selected ? options.find(slot => slot.minute === selected.minute + 60) : options[0];
    return <fieldset className={styles.parts} disabled={disabled || !options.length}>
        <legend>{label}</legend>
        <div className={styles.partControls}>
            <button type="button" className={styles.hourStep} aria-label={`${label} 한 시간 이전`} disabled={!previous} onClick={() => onChange(previous.time)}>−</button>
            <div className={styles.hourValue}>
            <select name={field} aria-label={`${label} 시`} value={hour} onChange={event => {
                const choices = options.filter(slot => Math.floor(slot.minute / 60) === Number(event.target.value));
                onChange((choices.find(slot => slot.minute % 60 === selected?.minute % 60) || choices[0]).time);
            }}><option value="" disabled>시</option>{hours.map(hour => <option key={hour} value={hour}>{String(hour % 24).padStart(2, '0')}시</option>)}</select>
            </div>
            <button type="button" className={styles.hourStep} aria-label={`${label} 한 시간 이후`} disabled={!next} onClick={() => onChange(next.time)}>+</button>
        </div>
        <div className={styles.minuteToggle} role="group" aria-label={`${label} 분`}>
            {[0, 30].map(minute => <button key={minute} type="button" aria-label={`${label} ${String(minute).padStart(2, '0')}분`} aria-pressed={Boolean(selected && selected.minute % 60 === minute)} disabled={!minutes.some(slot => slot.minute % 60 === minute)} onClick={() => onChange(timeLabel(hour * 60 + minute))}>{String(minute).padStart(2, '0')}분</button>)}
        </div>
    </fieldset>;
}

export default function TimeRangeMenu({ date, now, from, until, runningTime, update }) {
    const id = useId();
    const startButton = useRef(null);
    const endButton = useRef(null);
    const trigger = useRef(null);
    const panel = useRef(null);
    const [open, setOpen] = useState(null);
    const [draft, setDraft] = useState({ from: '', until: '' });
    const { upperBound, slots, starts } = timeOptions(date, now, runningTime);
    const start = minuteOf(draft.from);

    const startValid = starts.some(slot => slot.time === draft.from);
    const ends = slots.filter(slot => startValid && slot.minute > start && slot.time !== draft.from);
    const ready = startValid && ends.some(slot => slot.time === draft.until);
    useEffect(() => {
        if (!open) return;
        panel.current?.querySelector(`select[name="${open}"]`)?.focus();
        const previous = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = previous; };
    }, [open]);
    function show(field) {
        trigger.current = field === 'from' ? startButton.current : endButton.current;
        const validFrom = starts.some(slot => slot.time === from);
        const initialEnd = minuteOf(until);
        setDraft({ from: validFrom ? from : '', until: validFrom ? (initialEnd > minuteOf(from) && initialEnd <= upperBound ? until : timeLabel(minuteOf(from) + 30)) : '' });
        setOpen(field === 'until' && validFrom ? 'until' : 'from');
    }
    function close() { setOpen(null); trigger.current?.focus(); }
    function chooseStart(value) {
        const minute = minuteOf(value);
        setDraft(previous => {
            const previousEnd = minuteOf(previous.until);
            const keepEnd = previous.from && previousEnd !== null && previousEnd > minute;
            return { from: value, until: minute !== null ? (keepEnd ? previous.until : timeLabel(minute + 30)) : '' };
        });
    }
    function keyboard(event) {
        if (event.key === 'Escape') { event.stopPropagation(); close(); }
        if (event.key === 'Tab') {
            const controls = [...panel.current.querySelectorAll('button:not(:disabled), select:not(:disabled)')];
            const first = controls[0], last = controls.at(-1);
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
    }
    return <div className={styles.menu}>
        <div className={styles.summary}>
            <div className={styles.field}><span>최소 시작시간</span><GlassButton ref={startButton} aria-label="영화 최소 시작시간 선택" aria-expanded={Boolean(open)} aria-controls={open ? id : undefined} onClick={() => show('from')}><strong>{from}</strong></GlassButton></div>
            <span className={styles.rangeSeparator} aria-hidden="true">~</span>
            <div className={styles.field}><span>최대 시작시간</span><GlassButton ref={endButton} aria-label="영화 최대 시작시간 선택" aria-expanded={Boolean(open)} aria-controls={open ? id : undefined} onClick={() => show('until')}><strong>{until}</strong></GlassButton></div>
        </div>
        {open && createPortal(<div className={styles.overlay} role="dialog" aria-modal="true" aria-label="상영 시작 시간 범위 선택" onKeyDown={keyboard} onClick={event => { if (event.target === event.currentTarget) close(); }}>
            <section ref={panel} id={id} className={styles.panel} aria-label="상영 시작 시간 선택">
                <header className={styles.heading}><div className={styles.dateDetails}><h2><time dateTime={date}>{date.replaceAll('-', '.')}</time></h2><span>{runningTime > 0 ? `러닝타임 ${runningTime}분` : '러닝타임 미확인'}</span></div><button type="button" onClick={close} aria-label="시간 선택 닫기">×</button></header>
                <p className={styles.description}>선택한 시간 범위 안에 시작하는 상영 회차를 찾습니다.</p>
                <div className={styles.range}>
                    <TimeParts field="from" label="최소 시작시간" value={draft.from} options={starts} onChange={chooseStart} />
                    <span className={styles.modalSeparator} aria-hidden="true">~</span>
                    <TimeParts field="until" label="최대 시작시간" value={draft.until} options={ends} disabled={!startValid} onChange={until => setDraft(previous => ({ ...previous, until }))} />
                </div>
                {!starts.length && <p role="status">선택 가능한 시간이 없습니다. 날짜를 다시 선택해주세요.</p>}
                <footer className={styles.footer}><button className={styles.reset} type="button" onClick={() => { setDraft({ from: '', until: '' }); panel.current?.querySelector('select[name="from"]')?.focus(); }}>다시 선택</button><button className={styles.apply} type="button" disabled={!ready} onClick={() => { update(draft); close(); }}>이 시간으로 적용</button></footer>
            </section>
        </div>, document.body)}
    </div>;
}



