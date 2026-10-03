import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { cinemaTime, halfHour } from './state.js';
import GlassButton from '../components/GlassButton.jsx';
import styles from './TimeRangeMenu.module.css';

const timeLabel = minute => String(Math.floor(minute / 60) % 24).padStart(2, '0') + ':' + String(minute % 60).padStart(2, '0');
const minuteOf = time => halfHour(time) ? Number(time.slice(0, 2)) * 60 + Number(time.slice(3)) + (time < '04:00' ? 1440 : 0) : null;
const slots = Array.from({ length: 49 }, (_, i) => ({ minute: 240 + i * 30, time: timeLabel(240 + i * 30) }));
const periods = [{ label: '오전', min: 240, max: 720 }, { label: '오후', min: 720, max: 1080 }, { label: '저녁', min: 1080, max: 1440 }, { label: '다음 날 새벽', min: 1440, max: 1681 }];
const caption = (time, end = false) => time && (time < '04:00' || end && time === '04:00') ? '다음 날 ' + time : time;

export default function TimeRangeMenu({ date, now, from, until, update }) {
    const id = useId();
    const startButton = useRef(null);
    const endButton = useRef(null);
    const trigger = useRef(null);
    const panel = useRef(null);
    const list = useRef(null);
    const [open, setOpen] = useState(null);
    const [draft, setDraft] = useState({ from: '', until: '' });
    const start = minuteOf(draft.from);
    const end = draft.until === '04:00' ? 1680 : minuteOf(draft.until);
    const startValid = start !== null && cinemaTime(date, draft.from) > now;
    const ready = startValid && end !== null && end > start && draft.from !== draft.until;
    const options = slots.filter(slot => open === 'until' ? startValid && slot.minute > start && slot.time !== draft.from : slot.minute < 1680 && cinemaTime(date, slot.time) > now);
    const durations = startValid ? [2, 3, 4].filter(hours => start + hours * 60 <= 1680) : [];
    useEffect(() => {
        if (!open) return;
        panel.current?.focus();
        const selected = list.current?.querySelector('[aria-pressed="true"]');
        if (selected?.scrollIntoView) selected.scrollIntoView({ block: 'nearest' });
        else if (list.current) list.current.scrollTop = 0;
    }, [open]);
    useEffect(() => {
        if (!open) return;
        const previous = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = previous; };
    }, [open]);
    function show(field) {
        trigger.current = field === 'from' ? startButton.current : endButton.current;
        setDraft({ from, until });
        setOpen(field === 'until' && halfHour(from) && cinemaTime(date, from) > now ? 'until' : 'from');
    }
    function close() { setOpen(null); trigger.current?.focus(); }
    function choose(slot) {
        if (open === 'from') {
            setDraft({ from: slot.time, until: end !== null && end > slot.minute ? draft.until : '' });
            setOpen('until');
        } else setDraft(previous => ({ ...previous, until: slot.time }));
    }
    function keyboard(event) {
        if (event.key === 'Escape') { event.stopPropagation(); close(); }
        if (event.key === 'Tab') {
            const buttons = [...panel.current.querySelectorAll('button:not(:disabled)')];
            const first = buttons[0], last = buttons.at(-1);
            if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
    }
    return <div className={styles.menu}>
        <div className={styles.summary}>
            <GlassButton ref={startButton} aria-label="시작시간 선택" aria-expanded={Boolean(open)} aria-controls={id} onClick={() => show('from')}><strong>{caption(from) || '시작 시간'}</strong><small>부터</small></GlassButton>
            <span aria-hidden="true">—</span>
            <GlassButton ref={endButton} aria-label="종료시간 선택" aria-expanded={Boolean(open)} aria-controls={id} onClick={() => show('until')}><strong>{caption(until, true) || '종료 시간'}</strong><small>까지</small></GlassButton>
        </div>
        {open && createPortal(<div className={styles.overlay} role="dialog" aria-modal="true" aria-label="상영 시작 시간 범위 선택" onKeyDown={keyboard} onClick={event => { if (event.target === event.currentTarget) close(); }}>
            <section ref={panel} tabIndex={-1} id={id} className={styles.panel} aria-label={open === 'from' ? '시작시간 후보' : '종료시간 후보'}>
                <header className={styles.heading}><div><span>{date.replaceAll('-', '.')}</span><h2>언제 영화를 볼까요?</h2></div><button type="button" onClick={close} aria-label="시간 선택 닫기">×</button></header>
                <div className={styles.range}>
                    <button type="button" aria-pressed={open === 'from'} onClick={() => setOpen('from')}><span>이 시간부터</span><strong>{caption(draft.from) || '시작 선택'}</strong></button>
                    <span aria-hidden="true">—</span>
                    <button type="button" aria-pressed={open === 'until'} disabled={!startValid} onClick={() => setOpen('until')}><span>이 시간 전까지</span><strong>{caption(draft.until, true) || '종료 선택'}</strong></button>
                </div>
                <div className={styles.toolbar}><strong>{open === 'from' ? '시작 시간을 선택하세요' : '종료 시간을 선택하세요'}</strong>{open === 'until' && <div className={styles.presets}>{durations.map(hours => <button type="button" key={hours} aria-label={'+' + hours + '시간 ' + timeLabel(start + hours * 60)} onClick={() => setDraft(previous => ({ ...previous, until: timeLabel(start + hours * 60) }))}>+{hours}시간</button>)}</div>}</div>
                <div className={styles.timeList} ref={list}>
                    {periods.map(period => {
                        const choices = options.filter(slot => slot.minute >= period.min && slot.minute < period.max);
                        return choices.length > 0 && <div className={styles.period} key={period.label}><h3>{period.label}</h3><div className={styles.slots}>{choices.map(slot => <button type="button" key={slot.minute} aria-pressed={(open === 'from' ? draft.from : draft.until) === slot.time} onClick={() => choose(slot)}>{slot.time}</button>)}</div></div>;
                    })}
                    {!options.length && <p role="status">선택 가능한 시간이 없습니다. 날짜 또는 시작 시간을 다시 선택해주세요.</p>}
                </div>
                <footer className={styles.footer}><button className={styles.reset} type="button" onClick={() => { setDraft({ from: '', until: '' }); setOpen('from'); }}>다시 선택</button><button className={styles.apply} type="button" disabled={!ready} onClick={() => { update(draft); close(); }}>이 시간으로 적용</button></footer>
            </section>
        </div>, document.body)}
    </div>;
}
