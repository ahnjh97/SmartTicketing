import chairs from '../assets/seat-preferences/middle-middle.png';
import { getSeatLabel } from '../utils/seatLabels.js';

// Reuse the original dark and yellow chairs without changing their appearance.
const rows = ['FRONT', 'MIDDLE', 'REAR'];
const columns = [0, 410, 740, 1150];

export default function SeatZoneMap({ zone, className }) {
    return <svg className={className} viewBox="0 0 1480 875" role="img" aria-label={`${getSeatLabel(zone)} 구역 표시`}>
        {rows.flatMap((row, rowIndex) => columns.map((x, columnIndex) => {
            const position = `${columnIndex === 1 || columnIndex === 2 ? 'MIDDLE' : 'SIDE'}_${row}`;
            return <svg key={`${row}-${columnIndex}`} x={x} y={rowIndex * 300} width="330" height="275"
                viewBox={position === zone ? '410 400 320 275' : '20 70 330 275'} overflow="hidden" aria-hidden="true">
                <image href={chairs} width="1448" height="1086" />
            </svg>;
        }))}
    </svg>;
}
