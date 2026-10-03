import InlineDetails from '../components/InlineDetails.jsx';
import { useEffect, useState } from "react";
import { ticketApi } from "../api/tickets.js";
import { Link } from 'react-router-dom';
import glass from '../components/GlassButton.module.css';
import styles from './TicketsPage.module.css';

function formatDate(value) {
    return value
        ? new Date(value).toLocaleString("ko-KR", {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
        })
        : "-";
}

function formatTicketStatus(status) {
    switch (status) {
        case "VALID":
            return "사용 가능";
        case "USED":
            return "사용 처리됨";
        case "CANCELLED":
            return "취소됨";
        default:
            return status || "-";
    }
}

function formatDateOnly(value) {
    return value
        ? new Date(value).toLocaleDateString("ko-KR", {
            year: "numeric",
            month: "long",
            day: "numeric",
            weekday: "long",
        })
        : "-";
}

export default function TicketsPage() {
    const [tickets, setTickets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [expandedTicketId, setExpandedTicketId] = useState(null);

    useEffect(() => {
        let mounted = true;
        ticketApi.mine()
            .then((data) => {
                if (mounted) setTickets(Array.isArray(data) ? data : []);
            })
            .catch((e) => {
                if (mounted) setError(e?.message ?? "티켓을 불러오지 못했습니다.");
            })
            .finally(() => {
                if (mounted) setLoading(false);
            });
        return () => {
            mounted = false;
        };
    }, []);

    return (
        <section className={styles.page}>
            <h1>내 티켓</h1>
            <div className={styles.panel}>
                <h2>발급된 티켓</h2>
                {loading ? <p role="status">티켓을 불러오는 중입니다.</p> : error ? <p role="alert">{error}</p> : tickets.length === 0 ? (
                    <p>발급된 티켓이 없습니다.</p>
                ) : (
                    <div className={styles.ticketGroups}>
                        {Object.entries(
                            tickets.reduce((groups, ticket) => {
                                const dateKey = ticket.startTime
                                    ? new Date(ticket.startTime).toLocaleDateString("ko-KR")
                                    : "날짜 미정";
                                if (!groups[dateKey]) groups[dateKey] = [];
                                groups[dateKey].push(ticket);
                                return groups;
                            }, {})
                        ).map(([dateKey, dateTickets]) => (
                            <section key={dateKey} className={styles.ticketGroup}>
                                <h3 className={styles.dateHeading}>
                                    {dateTickets[0]?.startTime ? formatDateOnly(dateTickets[0].startTime) : dateKey}
                                </h3>
                                <div className={styles.tickets}>
                                    {dateTickets.map((ticket) => {
                                        const expanded = expandedTicketId === ticket.ticketId;

                                        return (
                                            <div key={ticket.ticketId} className={styles.ticketItem}>
                                                <article
                                                    className={`${styles.ticket}${expanded ? ` ${styles.expanded}` : ""}`}
                                                >
                                                    <button
                                                        type="button"
                                                        className={styles.ticketSummary}
                                                        aria-expanded={expanded}
                                                        onClick={() =>
                                                            setExpandedTicketId((current) =>
                                                                current === ticket.ticketId ? null : ticket.ticketId
                                                            )
                                                        }
                                                    >
                                                        <span className={styles.summaryMovie}>{ticket.movieTitle}</span>
                                                        <span className={styles.summaryTheater}>
                                                            <InlineDetails items={[ticket.theaterName]} />
                                                        </span>
                                                        <span className={styles.summaryTime}>
                                                            {formatDate(ticket.startTime)}
                                                        </span>
                                                        <span className={`${styles.summaryStatus} ${styles[`status_${ticket.status?.toLowerCase()}`] || ""}`}>
                                                            {formatTicketStatus(ticket.status)}
                                                        </span>
                                                        <span className={styles.expandIcon} aria-hidden="true">
                                                            {expanded ? "▲" : "▼"}
                                                        </span>
                                                    </button>
                                                </article>

                                                {expanded && (
                                                    <div className={styles.ticketDropdown}>
                                                        <p><InlineDetails items={[ticket.theaterName, ticket.screenName]} /></p>
                                                        <p>{formatDate(ticket.startTime)} ~ {formatDate(ticket.endTime)}</p>
                                                        <p>좌석: {ticket.seats?.join(", ") || "-"}</p>
                                                        <p>티켓 번호: <strong>{ticket.ticketNumber}</strong></p>
                                                        <p>QR: {ticket.qrCode}</p>
                                                        <p>상태: {formatTicketStatus(ticket.status)}</p>
                                                        {ticket.groupId && (
                                                            <Link
                                                                className={glass.button}
                                                                to={`/booking/restore?group=${ticket.groupId}`}
                                                            >
                                                                예약 상세 및 전체 취소
                                                            </Link>
                                                        )}
                                                    </div>
                                                )}
                                            </div>
                                        );
                                    })}
                                </div>
                            </section>
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}
