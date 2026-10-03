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
                                            <div key={ticket.ticketId} className={`${styles.ticketItem}${expanded ? ` ${styles.isExpanded}` : ""}`}>
                                                <article
                                                    key={ticket.ticketId}
                                                    className={styles.ticket}
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
                                                        <span className={styles.summaryStatus}>
                                                            {formatTicketStatus(ticket.status)}
                                                        </span>
                                                    </button>
                                                </article>
                                            </div>
                                        );
                                    })}
                                </div>
                            </section>
                        ))}
                    </div>
                )}
            </div>
        {expandedTicketId && (() => {
            const selectedTicket = tickets.find((ticket) => ticket.ticketId === expandedTicketId);
            if (!selectedTicket) return null;

            return (
                <div
                    className={styles.ticketModalBackdrop}
                    role="presentation"
                    onClick={() => setExpandedTicketId(null)}
                >
                    <section
                        className={styles.ticketModal}
                        role="dialog"
                        aria-modal="true"
                        aria-label="티켓 상세"
                        onClick={(event) => event.stopPropagation()}
                    >
                        <button
                            type="button"
                            className={styles.ticketModalClose}
                            aria-label="티켓 상세 닫기"
                            onClick={() => setExpandedTicketId(null)}
                        >
                            ×
                        </button>

                        <div className={styles.ticketCard}>
                            <div className={styles.ticketCardHeader}>
                                <span className={styles.ticketLabel}>MOVIE TICKET</span>
                                <span className={styles.ticketStatus}>{formatTicketStatus(selectedTicket.status)}</span>
                            </div>

                            <div className={styles.ticketCardMovie}>
                                <h2>{selectedTicket.movieTitle}</h2>
                                <p><InlineDetails items={[selectedTicket.theaterName, selectedTicket.screenName]} /></p>
                            </div>

                            <div className={styles.ticketCardInfo}>
                                <div>
                                    <span>DATE</span>
                                    <strong>{formatDate(selectedTicket.startTime)}</strong>
                                </div>
                                <div>
                                    <span>SEAT</span>
                                    <strong>{selectedTicket.seats?.join(", ") || "-"}</strong>
                                </div>
                            </div>

                            <div className={styles.ticketCardDivider}>
                                <span />
                                <span />
                            </div>

                            <div className={styles.ticketCardBottom}>
                                <div>
                                    <span>티켓 번호</span>
                                    <strong>{selectedTicket.ticketNumber}</strong>
                                </div>
                                <div className={styles.ticketQrPlaceholder}>
                                    <span>QR</span>
                                    <small>{selectedTicket.qrCode}</small>
                                </div>
                            </div>

                            {selectedTicket.groupId && (
                                <Link
                                    className={glass.button}
                                    to={`/booking/restore?group=${selectedTicket.groupId}`}
                                >
                                    예약 상세 및 전체 취소
                                </Link>
                            )}
                        </div>
                    </section>
                </div>
            );
        })()}
        </section>
    );
}
