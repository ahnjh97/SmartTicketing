import { QRCodeSVG } from "qrcode.react";
import styles from "../pages/TicketsPage.module.css";

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

function formatTicketStatus(status) {
    switch (status) {
        case "VALID": return "사용 가능";
        case "USED": return "사용 처리됨";
        case "CANCELLED": return "취소됨";
        case "EXPIRED": return "만료됨";
        default: return status || "-";
    }
}

export default function HeaderTicketPreview({ ticket, onMouseEnter, onMouseLeave, onClose }) {
    if (!ticket) return null;

    return (
        <div className={styles.ticketModalBackdrop}>
            <section
                className={styles.ticketModal}
                aria-label="티켓 상세 미리보기"
                onMouseEnter={onMouseEnter}
                onMouseLeave={onMouseLeave}
            >
                <button
                    type="button"
                    className={styles.ticketModalClose}
                    aria-label="티켓 상세 닫기"
                    onClick={onClose}
                >
                    <span aria-hidden="true">×</span>
                </button>

                <div className={styles.ticketCard}>
                    <div className={styles.ticketCardHeader}>
                        <span className={styles.ticketLabel}>CINEMA PASS</span>
                        <span className={styles.ticketStatus}>{formatTicketStatus(ticket.status)}</span>
                    </div>

                    <div className={styles.ticketCardContent}>
                        <div className={styles.ticketCardLeft}>
                            <div className={styles.ticketCardMovie}>
                                <span className={styles.ticketInfoLabel}>MOVIE</span>
                                <h2>{ticket.movieTitle || "-"}</h2>
                            </div>
                            <div className={styles.ticketCardInfo}>
                                <div><span>THEATER</span><strong>{ticket.theaterName || "-"} , {ticket.screenName || "-"}</strong></div>
                                <div><span>SEAT</span><strong>{ticket.seats?.join(", ") || "-"}</strong></div>
                                <div><span>TIME</span><strong>{formatTime(ticket.startTime)} ~ {formatTime(ticket.endTime)}</strong></div>
                            </div>
                        </div>

                        <div className={styles.ticketCardRight}>
                            <div className={styles.ticketQrPlaceholder}>
                                <QRCodeSVG
                                    value={`${TICKET_VERIFY_PAGE_URL}/#/ticket/verify/${encodeURIComponent(ticket.qrCode || ticket.ticketNumber)}`}
                                    size={220}
                                    marginSize={2}
                                    level="M"
                                    title={`티켓 QR - ${ticket.ticketNumber}`}
                                />
                            </div>
                            <div className={styles.ticketNumber}>
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
