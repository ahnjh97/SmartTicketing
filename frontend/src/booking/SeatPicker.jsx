import styles from './SeatPicker.module.css';

export default function SeatPicker({ seats, selected, limit, onChange, disabled = false }) {
    const rows = new Map();
    for (const seat of [...seats].sort((a, b) => a.row.localeCompare(b.row, 'ko', { numeric: true }) || a.number - b.number)) {
        if (!rows.has(seat.row)) rows.set(seat.row, []);
        rows.get(seat.row).push(seat);
    }
    const toggle = id => onChange(selected.includes(id) ? selected.filter(value => value !== id)
        : selected.length < limit ? [...selected, id] : selected);
    return <section className={styles.picker} aria-label="좌석 직접 선택">
        <div className={styles.screen} aria-label="스크린">SCREEN</div>
        <div className={styles.viewport} tabIndex={0} aria-label="좌석 배치, 좁은 화면에서는 가로로 이동할 수 있습니다">
            <div className={styles.rows}>{[...rows].map(([row, items]) => <div key={row} className={styles.row}>
                <span className={styles.rowName}>{row}</span>
                {items.map((seat, index) => {
                    const chosen = selected.includes(seat.id);
                    const unavailable = seat.status !== 'AVAILABLE';
                    const aisle = index > 0 && seat.segment && items[index - 1].segment && seat.segment !== items[index - 1].segment;
                    return <button key={seat.id} type="button" className={`${styles.seat} ${aisle ? styles.aisle : ''}`}
                        aria-label={`${seat.row}${seat.number} ${unavailable ? '선택 불가' : '좌석'}`}
                        aria-pressed={chosen} disabled={disabled || unavailable || (!chosen && selected.length >= limit)}
                        data-unavailable={unavailable} onClick={() => toggle(seat.id)}>{unavailable ? '×' : seat.number}</button>;
                })}
                <span className={styles.rowName}>{row}</span>
            </div>)}</div>
        </div>
        <div className={styles.legend}><span><i />선택 가능</span><span><i className={styles.selected} />선택 중</span><span><i className={styles.unavailable} />선택 불가</span></div>
        <p className={styles.note}>선택만으로 좌석이 확보되지 않습니다. ‘좌석 선점’ 후 서버가 확정한 좌석을 5분 동안 보관합니다.</p>
    </section>;
}
