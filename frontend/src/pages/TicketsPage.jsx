import InlineDetails from '../components/InlineDetails.jsx';
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState } from "react";
import { ticketApi } from "../api/tickets.js";
import styles from './TicketsPage.module.css';

const PUBLIC_TICKET_BASE_URL = "https://ahnj97.github.io/SmartTicketing";

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

function formatTime(value) {
    return value
        ? new Date(value)
            .toLocaleTimeString("en-US", {
                hour: "numeric",
                minute: "2-digit",
                hour12: true,
            })
            .toLowerCase()
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
    const [verificationPhase, setVerificationPhase] = useState("idle");
    const [verificationSeconds, setVerificationSeconds] = useState(3);
    const [selectedDate, setSelectedDate] = useState("ALL");
    const [selectedDate, setSelectedDate] = useState("ALL");

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

    useEffect(() => {
        if (!expandedTicketId) {
            setVerificationPhase("idle");
            return;
        }

        const selected = tickets.find((ticket) => ticket.ticketId === expandedTicketId);
        if (!selected) {
            setVerificationPhase("idle");
            return;
        }

        setVerificationPhase(selected.status === "USED" ? "used" : "idle");
    }, [expandedTicketId, tickets]);

    useEffect(() => {
        if (verificationPhase !== "processing") return;

        const timer = window.setInterval(() => {
            setVerificationSeconds((current) => Math.max(0, current - 1));
        }, 1000);

        return () => window.clearInterval(timer);
    }, [verificationPhase]);

    const handleVerifyDemo = () => {
        const selected = tickets.find((ticket) => ticket.ticketId === expandedTicketId);
        if (!selected || selected.status !== "VALID" || verificationPhase === "processing") return;

        setVerificationSeconds(3);
        setVerificationPhase("processing");

        window.setTimeout(async () => {
            try {
                const result = await ticketApi.completeVerify(selected.qrCode || selected.ticketNumber);
                if (!result?.used) throw new Error(result?.message || "티켓 사용 처리에 실패했습니다.");
                setTickets((currentTickets) => currentTickets.map((ticket) => ticket.ticketId === selected.ticketId ? { ...ticket, status: "USED" } : ticket));
                setVerificationPhase("used");
            } catch (e) {
                setVerificationPhase("idle");
                setError(e?.message ?? "티켓 사용 처리에 실패했습니다.");
            }
        }, 3000);
    };

    return (
        <section className={styles.page}>
            <h1>내 티켓</h1>
            <div className={styles.panel}>
                <div className={styles.ticketFilterHeader}>
                    <h2>발급된 티켓</h2>
                    <select className={styles.ticketDateFilter} value={selectedDate} onChange={(event) => { setSelectedDate(event.target.value); setExpandedTicketId(null); }} aria-label="티켓 날짜 필터">
                        <option value="ALL">전체 날짜</option>
                        {ticketDates.map((date) => <option key={date} value={date}>{new Date(date + "T00:00:00").toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric" })}</option>)}
                    </select>
                </div>
                {loading ? <p role="status">티켓을 불러오는 중입니다.</p> : error ? <p role="alert">{error}</p> : filteredTickets.length === 0 ? (
                    <p>발급된 티켓이 없습니다.</p>
                ) : (
                    <div className={styles.ticketGroups}>
                        {Object.entries(
                            filteredTickets.reduce((groups, ticket) => {
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
                                                <article className={styles.ticket}>
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
                            <div className={styles.ticketCard}>
                                <div className={styles.ticketCardHeader}>
                                    <span className={styles.ticketLabel}>CINEMA PASS</span>
                                    <span className={styles.ticketStatus}>
                                        {verificationPhase === "used" ? "사용 처리됨" : formatTicketStatus(selectedTicket.status)}
                                    </span>
                                </div>

                                <div className={styles.ticketCardContent}>
                                    <div className={styles.ticketCardLeft}>
                                        <div className={styles.ticketCardMovie}>
                                            <span className={styles.ticketInfoLabel}>MOVIE</span>
                                            <h2>{selectedTicket.movieTitle}</h2>
                                        </div>

                                        <div className={styles.ticketCardInfo}>
                                            <div>
                                                <span>THEATER</span>
                                                <strong>
                                                    {selectedTicket.theaterName || "-"} , {selectedTicket.screenName || "-"}
                                                </strong>
                                            </div>
                                            <div>
                                                <span>SEAT</span>
                                                <strong>{selectedTicket.seats?.join(", ") || "-"}</strong>
                                            </div>
                                            <div>
                                                <span>TIME</span>
                                                <strong>
                                                    {formatTime(selectedTicket.startTime)} ~ {formatTime(selectedTicket.endTime)}
                                                </strong>
                                            </div>
                                        </div>
                                    </div>

                                    <div className={styles.ticketCardRight}>
                                        <span className={styles.ticketStubLabel}>ADMIT ONE</span>
                                        <div className={styles.ticketQrPlaceholder}>
                                            <QRCodeSVG
                                                value={`${PUBLIC_TICKET_BASE_URL}/ticket/verify/${encodeURIComponent(selectedTicket.qrCode || selectedTicket.ticketNumber)}`}
                                                size={220}
                                                marginSize={2}
                                                level="M"
                                                title={`티켓 QR - ${selectedTicket.ticketNumber}`}
                                            />
                                            {verificationPhase === "processing" && (
                                                <div className={styles.qrProcessingOverlay}>
                                                    <strong>처리 중입니다...</strong>
                                                    <span>{verificationSeconds}초</span>
                                                </div>
                                            )}
                                        </div>

                                        {verificationPhase === "used" && (
                                            <div className={styles.ticketUsedStamp}>USED</div>
                                        )}

                                        {selectedTicket.status === "VALID" && verificationPhase !== "used" && (
                                            <button
                                                type="button"
                                                className={styles.verifyDemoButton}
                                                onClick={handleVerifyDemo}
                                                disabled={verificationPhase === "processing"}
                                            >
                                                {verificationPhase === "processing" ? "처리 중..." : "QR 사용 처리 테스트"}
                                            </button>
                                        )}

                                        <div className={styles.ticketNumber}>
                                            <span>TICKET NO.</span>
                                            <strong>{selectedTicket.ticketNumber}</strong>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </section>
                    </div>
                );
            })()}
        </section>
    );
}
