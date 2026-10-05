import InlineDetails from '../components/InlineDetails.jsx';
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useRef, useState } from "react";
import GlassButton from '../components/GlassButton.jsx';
import { ticketApi } from "../api/tickets.js";
import styles from './TicketsPage.module.css';

const PUBLIC_TICKET_BASE_URL = 'https://ahnj97.github.io/SmartTicketing';

function formatTime(value) {
    return value
        ? new Date(value)
            .toLocaleTimeString("ko-KR", {
                timeZone: 'Asia/Seoul',
                hour: "2-digit",
                minute: "2-digit",
                hourCycle: 'h23',
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
        case "EXPIRED":
            return "만료됨";
        default:
            return status || "-";
    }
}

function formatDateOnly(value) {
    return value
        ? dateKey(value).replaceAll('-', '.')
        : "-";
}

function dateKey(value) {
    return new Date(value).toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

export default function TicketsPage() {
    const modal = useRef(null);
    const [tickets, setTickets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [expandedTicketId, setExpandedTicketId] = useState(null);
    const [verificationPhase, setVerificationPhase] = useState("idle");
    const [verificationSeconds, setVerificationSeconds] = useState(5);
    const [selectedDate, setSelectedDate] = useState("ALL");
    const [cancellingTicketId, setCancellingTicketId] = useState(null);

    useEffect(() => {
        if (!expandedTicketId) return;
        const previous = document.activeElement;
        const overflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        modal.current?.querySelector('button')?.focus();
        const onKeyDown = event => {
            if (event.key === 'Escape') setExpandedTicketId(null);
            if (event.key === 'Tab') {
                event.preventDefault();
                modal.current?.querySelector('button')?.focus();
            }
        };
        document.addEventListener('keydown', onKeyDown);
        return () => {
            document.removeEventListener('keydown', onKeyDown);
            document.body.style.overflow = overflow;
            previous?.focus();
        };
    }, [expandedTicketId]);

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
        if (verificationPhase !== "processing") return;

        const timer = window.setInterval(() => {
            setVerificationSeconds((current) => Math.max(0, current - 1));
        }, 1000);

        return () => window.clearInterval(timer);
    }, [verificationPhase]);

    useEffect(() => {
        if (!expandedTicketId) return;

        const selected = tickets.find((ticket) => ticket.ticketId === expandedTicketId);
        if (!selected || selected.status !== "VALID") return;

        let stopped = false;
        let timer;

        const refresh = async () => {
            try {
                const result = await ticketApi.verifyStatus(selected.qrCode || selected.ticketNumber);
                if (stopped) return;

                if (result?.used || result?.ticket?.status === "USED") {
                    setTickets((currentTickets) =>
                        currentTickets.map((ticket) =>
                            ticket.ticketId === selected.ticketId
                                ? { ...ticket, status: "USED" }
                                : ticket
                        )
                    );
                    setVerificationPhase("used");
                    return;
                }

                setVerificationPhase(result?.processing ? "processing" : "idle");
                timer = window.setTimeout(refresh, 1000);
            } catch {
                if (!stopped) timer = window.setTimeout(refresh, 2000);
            }
        };

        refresh();

        return () => {
            stopped = true;
            if (timer) window.clearTimeout(timer);
        };
    }, [expandedTicketId, tickets]);

    async function cancelTicket(ticket) {
        if (ticket.status !== "VALID" || !ticket.reservationId) return;
        if (!window.confirm("이 예매를 취소하시겠습니까?\n취소하면 좌석이 다시 예매 가능 상태가 됩니다.")) return;

        setCancellingTicketId(ticket.ticketId);
        setError("");
        try {
            await ticketApi.cancelReservation(ticket.reservationId, crypto.randomUUID());
            setTickets((currentTickets) =>
                currentTickets.map((current) =>
                    current.ticketId === ticket.ticketId
                        ? { ...current, status: "CANCELLED" }
                        : current
                )
            );
        } catch (e) {
            setError(e?.message ?? "예매 취소에 실패했습니다.");
        } finally {
            setCancellingTicketId(null);
        }
    }

    function selectTicket(id) {
        setExpandedTicketId(id);
        setVerificationPhase(tickets.find(ticket => ticket.ticketId === id)?.status === 'USED' ? 'used' : 'idle');
        setVerificationSeconds(5);
    }
    const ticketDates = [...new Set(tickets.map((ticket) => ticket.startTime ? dateKey(ticket.startTime) : null).filter(Boolean))].sort((a, b) => b.localeCompare(a));
    const filteredTickets = (selectedDate === "ALL" ? tickets : tickets.filter((ticket) => ticket.startTime && dateKey(ticket.startTime) === selectedDate))
        .toSorted((a, b) => (Date.parse(b.startTime) || 0) - (Date.parse(a.startTime) || 0));

    return (
        <section className={styles.page}>
            <h1>내 티켓</h1>
            <div className={styles.panel}>
                <div className={styles.ticketFilterHeader}>
                    <h2>발급된 티켓 <span className={styles.count}>{filteredTickets.length}</span></h2>
                    <select className={styles.ticketDateFilter} value={selectedDate} onChange={(event) => { setSelectedDate(event.target.value); selectTicket(null); }} aria-label="티켓 날짜 필터">
                        <option value="ALL">전체 날짜</option>
                        {ticketDates.map((date) => <option key={date} value={date}>{date.replaceAll('-', '.')}</option>)}
                    </select>
                </div>
                {loading ? null : error ? <p role="alert">{error}</p> : filteredTickets.length === 0 ? (
                    <p className={styles.empty}>{selectedDate === 'ALL' ? '발급된 티켓이 없습니다.' : '선택한 날짜의 티켓이 없습니다.'}</p>
                ) : (
                    <div className={styles.ticketGroups}>
                        {Object.entries(
                            filteredTickets.reduce((groups, ticket) => {
                                const dateKey = ticket.startTime
                                    ? formatDateOnly(ticket.startTime)
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
                                        const cancelling = cancellingTicketId === ticket.ticketId;

                                        return (
                                            <div key={ticket.ticketId} className={`${styles.ticketItem}${expanded ? ` ${styles.isExpanded}` : ""}`}>
                                                <article className={styles.ticket}>
                                                    {ticket.status === "CANCELLED" && (
                                                        <div className={styles.ticketCancelledStamp}>CANCELLED</div>
                                                    )}
                                                    {ticket.status === "EXPIRED" && (
                                                        <div className={styles.ticketCancelledStamp}>EXPIRED</div>
                                                    )}
                                                    <button
                                                        type="button"
                                                        className={styles.ticketSummary}
                                                        aria-expanded={expanded}
                                                        onClick={() => {
                                                            if (ticket.status === "USED") return;
                                                            selectTicket(expandedTicketId === ticket.ticketId ? null : ticket.ticketId);
                                                        }}
                                                        disabled={ticket.status === "USED"}
                                                    >
                                                        <span className={styles.summaryTop}>
                                                            <span className={styles.summaryStatus} data-status={ticket.status}>{formatTicketStatus(ticket.status)}</span>
                                                        </span>
                                                        <strong className={styles.summaryMovie}>{ticket.movieTitle}</strong>
                                                        <span className={styles.summaryTheater}>
                                                            <InlineDetails items={[ticket.theaterName, ticket.screenName]} />
                                                        </span>
                                                        <span className={styles.summaryBottom}>
                                                            <span className={styles.summaryTime}>{formatTime(ticket.startTime)}<small>종료 {formatTime(ticket.endTime)}</small></span>
                                                            <span className={styles.summarySeats}><small>좌석</small><strong>{ticket.seats?.join(', ') || '-'}</strong></span>
                                                        </span>
                                                    </button>
                                                    {ticket.status === "VALID" && (
                                                        <button
                                                            type="button"
                                                            className={styles.cancelButton}
                                                            disabled={cancelling}
                                                            onClick={() => cancelTicket(ticket)}
                                                        >
                                                            {cancelling ? "취소 중..." : "예매 취소"}
                                                        </button>
                                                    )}
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
                        onClick={() => selectTicket(null)}
                    >
                        <section
                            ref={modal}
                            className={styles.ticketModal}
                            role="dialog"
                            aria-modal="true"
                            aria-label="티켓 상세"
                            onClick={(event) => event.stopPropagation()}
                        >
                            <div className={styles.ticketCard}>
                                <div className={styles.ticketCardHeader}>
                                    <span className={styles.ticketStatus} data-status={selectedTicket.status}>
                                        {verificationPhase === "used" ? "사용 처리됨" : formatTicketStatus(selectedTicket.status)}
                                    </span>
                                    <GlassButton className={styles.closeButton} aria-label="티켓 상세 닫기" onClick={() => selectTicket(null)}>×</GlassButton>
                                </div>

                                <div className={styles.ticketCardContent}>
                                    <div className={styles.ticketCardLeft}>
                                        <div className={styles.ticketCardMovie}>
                                            <h2>{selectedTicket.movieTitle}</h2>
                                            <p className={styles.detailDate}>{formatDateOnly(selectedTicket.startTime)}</p>
                                        </div>

                                        <div className={styles.ticketCardInfo}>
                                            <div>
                                                <span>극장</span>
                                                <strong>
                                                    <InlineDetails items={[selectedTicket.theaterName, selectedTicket.screenName]} />
                                                </strong>
                                            </div>
                                            <div>
                                                <span>좌석</span>
                                                <strong>{selectedTicket.seats?.join(", ") || "-"}</strong>
                                            </div>
                                            <div>
                                                <span>상영 시간</span>
                                                <strong className={styles.detailTime}>
                                                    {formatTime(selectedTicket.startTime)} <small>→ {formatTime(selectedTicket.endTime)}</small>
                                                </strong>
                                            </div>
                                        </div>
                                    </div>

                                    <div className={styles.ticketCardRight}>
                                        <span className={styles.ticketStubLabel}>입장 QR</span>
                                        <div className={styles.ticketQrPlaceholder}>
                                            <QRCodeSVG
                                                value={`${PUBLIC_TICKET_BASE_URL}/#/ticket/verify/${encodeURIComponent(selectedTicket.qrCode || selectedTicket.ticketNumber)}`}
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

                                        <div className={styles.ticketNumber}>
                                            <span>티켓 번호</span>
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
