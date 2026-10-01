import { useEffect, useId, useRef, useState } from 'react';
import GlassButton from '../components/GlassButton.jsx';
import styles from './TimeRangeMenu.module.css';

const periods = ['새벽', '오전', '오후', '저녁'];
const slots = Array.from({ length: 48 }, (_, i) => ({
    minute: i * 30, time: String(Math.floor(i / 2)).padStart(2, '0') + ':' + (i % 2 ? '30' : '00'),
    period: Math.floor(i / 12),
}));
export default function TimeRangeMenu({ date, now, from, until, update }) {
    const id = useId();
    const startButton = useRef(null);
    const endButton = useRef(null);
    const [open, setOpen] = useState(null);
    const [view, setView] = useState(null);
    const panel = useRef(null);
    useEffect(() => { if (open) panel.current?.focus(); }, [open]);
    const start = /^(?:[01]\d|2[0-3]):(00|30)$/.test(from) ? Number(from.slice(0, 2)) * 60 + Number(from.slice(3)) : null;
    const startValid = start !== null && Date.parse(date + 'T' + from + ':00+09:00') > now;
    const options = open === 'until'
        ? startValid ? [0, 1].flatMap(day => slots.filter(s => s.minute + day * 1440 > start && s.minute + day * 1440 < start + 1440).map(s => ({ ...s, day }))) : []
        : slots.filter(s => Date.parse(date + 'T' + s.time + ':00+09:00') > now).map(s => ({ ...s, day: 0 }));
    const groups = [...new Set(options.map(s => s.day + ':' + s.period))];
    const current = groups.includes(view) ? view : groups[0];
    function close() { setOpen(null); (open === 'from' ? startButton : endButton).current?.focus(); }
    function toggle(field) {
        setOpen(open === field ? null : field);
        const value = field === 'from' ? from : until;
        const minute = /^(?:[01]\d|2[0-3]):(00|30)$/.test(value) ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3)) : null;
        setView(minute === null ? null : (field === 'until' && until < from ? 1 : 0) + ':' + Math.floor(minute / 360));
    }
    function choose(slot) {
        if (open === 'from') {
            update({ from: slot.time, until: null }); setView(null); setOpen('until');
            endButton.current?.focus();
        } else { update({ until: slot.time }); close(); }
    }
    return <div className={styles.menu} onKeyDown={e => { if (e.key === 'Escape' && open) { e.stopPropagation(); close(); } }}>
        <div className={styles.summary}>
            <GlassButton ref={startButton} aria-label="시작시간 선택" aria-expanded={open === 'from'} aria-controls={id} onClick={() => toggle('from')}>
                <small>시작</small><strong>{from || '시간 선택'}</strong><span aria-hidden="true">⌄</span>
            </GlassButton>
            <span aria-hidden="true">~</span>
            <GlassButton ref={endButton} aria-label="종료시간 선택" disabled={!startValid} aria-expanded={open === 'until'} aria-controls={id} onClick={() => toggle('until')}>
                <small>종료</small><strong>{until ? (until < from ? '익일 ' : '') + until : '시간 선택'}</strong><span aria-hidden="true">⌄</span>
            </GlassButton>
        </div>
        {open && <section ref={panel} tabIndex={-1} id={id} className={styles.panel} aria-label={open === 'from' ? '시작시간 후보' : '종료시간 후보'}>
            <div className={styles.heading}><strong>{open === 'from' ? '언제부터 볼까요?' : '몇 시 시작 영화까지 볼까요?'}</strong><button type="button" onClick={close} aria-label="시간 선택 닫기">×</button></div>
            <p>{open === 'from' ? '30분 간격 · 오늘 지난 시간은 제외됩니다.' : '선택한 종료 시각에 시작하는 영화는 제외됩니다.'}</p>
            {[0, 1].map(day => {
                const dayGroups = groups.filter(g => g.startsWith(day + ':'));
                return dayGroups.length > 0 && <div className={styles.groups} key={day} aria-label={day ? '다음 날 시간대' : '선택 날짜 시간대'}>
                    {open === 'until' && <small>{day ? '다음 날' : '선택 날짜'}</small>}
                    {dayGroups.map(g => <button key={g} type="button" aria-pressed={g === current} onClick={() => setView(g)}>{day ? '익일 ' : ''}{periods[Number(g.split(':')[1])]}</button>)}
                </div>;
            })}
            <div className={styles.slots}>{options.filter(s => s.day + ':' + s.period === current).map(s => <button type="button" key={s.day + ':' + s.time} aria-pressed={(open === 'from' ? from : until) === s.time} onClick={() => choose(s)}>{s.day ? '익일 ' : ''}{s.time}</button>)}</div>
            {!options.length && <p role="status">선택 가능한 시간이 없습니다. 날짜 또는 시작 시간을 다시 선택해주세요.</p>}
        </section>}
        {(from || until) && <button className={styles.reset} type="button" onClick={() => { update({ from: null, until: null }); setOpen(null); startButton.current?.focus(); }}>시간 초기화</button>}
    </div>;
}
