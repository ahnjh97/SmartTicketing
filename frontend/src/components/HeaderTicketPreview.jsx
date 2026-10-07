import { QRCodeSVG } from "qrcode.react";
import styles from "./HeaderTicketPreview.module.css";

const TICKET_VERIFY_PAGE_URL = import.meta.env.DEV
    ? window.location.origin
    : "https://smartticketing.duckdns.org";

function formatTime(value) {
    return value
        ? new Date(value).toLocaleTimeString("ko-KR", {
            timeZone: "Asia/Seoul",
            hour: "2-digit",
            minute: "2-digit",
            hourCycle: "h23",
        })
        : "-";
}

export default function HeaderTicketPreview({ ticket, onMouseEnter, onMouseLeave }) {
    if (!ticket) return null;

    return (
        <div className={styles.backdrop} aria-hidden="true">
            <section
                className={styles.modal}
                aria-label="티켓 상세 미리보기"
                onMouseEnter={onMouseEnter}
                onMouseLeave={onMouseLeave}
            >
                <div className={styles.card}>
                    <div className={styles.header}>
                        <span className={styles.label}>CINEMA PASS</span>
                        <span className={styles.status}>사용 가능</span>
                    </div>

                    <div className={styles.content}>
                        <div className={styles.left}>
                            <div className={styles.movie}>
                                <span>MOVIE</span>
                                <h2>{ticket.movieTitle || "-"}</h2>
                            </div>

                            <div className={styles.info}>
                                <div>
                                    <span>THEATER</span>
                                    <strong>{ticket.theaterName || "-"} · {ticket.screenName || "-"}</strong>
                                </div>
                                <div>
                                    <span>SEAT</span>
                                    <strong>{ticket.seats?.join(", ") || "-"}</strong>
                                </div>
                                <div>
                                    <span>TIME</span>
                                    <strong>{formatTime(ticket.startTime)} ~ {formatTime(ticket.endTime)}</strong>
                                </div>
                            </div>
                        </div>

                        <div className={styles.right}>
                            <div className={styles.qr}>
                                <QRCodeSVG
                                    value={`${TICKET_VERIFY_PAGE_URL}/#/ticket/verify/${encodeURIComponent(ticket.qrCode || ticket.ticketNumber)}`}
                                    size={178}
                                    marginSize={2}
                                    level="M"
                                    title={`티켓 QR - ${ticket.ticketNumber}`}
                                />
                            </div>
                            <div className={styles.number}>
                                <span>TICKET NO.</span>
                                <strong>{ticket.ticketNumber || "-"}</strong>
                            </div>
                        </div>
                    </div>
                </div>
            </section>
        </div>
    );
}
