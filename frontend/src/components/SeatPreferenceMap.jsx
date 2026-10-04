import chairs from "../assets/seat-preferences/middle-middle.png";
import { getSeatLabel } from "../utils/seatLabels.js";
import styles from "./SeatPreferenceMap.module.css";

const ROWS = ["FRONT", "MIDDLE", "REAR"];

export default function SeatPreferenceMap({ seats = [] }) {
    const priorities = new Map();
    seats.forEach((seat, index) => {
        const position = typeof seat === "string" ? seat : seat?.position;
        if (position && !priorities.has(position)) {
            priorities.set(position, typeof seat === "string" ? index + 1 : seat.priority ?? index + 1);
        }
    });
    const description = [...priorities]
        .map(([position, priority]) => `${priority}위 ${getSeatLabel(position)}`)
        .join(", ");

    return (
        <div className={styles.map} role="img" aria-label={`선호 좌석: ${description || "미설정"}`}>
            {ROWS.flatMap(row => ["left", "center", "right"].map(column => {
                const position = `${column === "center" ? "MIDDLE" : "SIDE"}_${row}`;
                const priority = priorities.get(position);
                const selected = priority !== undefined;
                return (
                    <div key={`${row}-${column}`} className={styles.zone} aria-hidden="true">
                        {Array.from({ length: column === "center" ? 2 : 1 }, (_, index) => (
                            <svg key={index} className={styles.chair}
                                viewBox="20 70 330 275"
                                aria-hidden="true" focusable="false">
                                <image href={chairs} width="1448" height="1086" />
                            </svg>
                        ))}
                        {selected && <span className={styles.rank}><strong>{priority}</strong><small>위</small></span>}
                    </div>
                );
            }))}
        </div>
    );
}
