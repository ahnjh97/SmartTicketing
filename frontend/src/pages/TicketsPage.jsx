import InlineDetails from '../components/InlineDetails.jsx';
import { QRCodeSVG } from "qrcode.react";
import { useEffect, useRef, useState } from "react";
import { ticketApi } from "../api/tickets.js";
import styles from './TicketsPage.module.css';

const TICKET_VERIFY_PAGE_URL = import.meta.env.DEV
    ? window.location.origin
    : 'https://smartticketing.duckdns.org';

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

function isShowtimeStarted(ticket) {
    return ticket?.startTime && Date.now() >= Date.parse(ticket.startTime);
}

export default function TicketsPage() {
    const modal = useRef(null);
    const [tickets, setTickets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [nextCursor, setNextCursor] = useState(null);
    const [loadingMore, setLoadingMore] = useState(false);
    const [selectedTicketId, setExpandedTicketId] = useState(null);
    const selectedTicket = tickets.find(ticket => ticket.ticketId === selectedTicketId);
    const expandedTicketId = selectedTicket && !["EXPIRED", "CANCELLED"].includes(selectedTicket.status) ? selectedTicketId : null;
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
        ticketApi.page()
            .then((data) => {
                if (mounted) { setTickets(data.items); setNextCursor(data.nextCursor); }
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

    async function loadMore() {
        if (loadingMore || !nextCursor) return;
        setLoadingMore(true);
        setError("");
        try {
            const data = await ticketApi.page(nextCursor);
            setTickets(items => [...items, ...data.items.filter(t => !items.some(item => item.ticketId === t.ticketId))]);
            setNextCursor(data.nextCursor);
        } catch (e) { setError(e?.message ?? "추가 티켓을 불러오지 못했습니다."); }
        finally { setLoadingMore(false); }
    }

    useEffect(() => {
        if (!expandedTicketId || verificationPhase !== "processing") return;

        const timer = window.setInterval(() => {
            setVerificationSeconds((current) => Math.max(0, current - 1));
        }, 1000);

        return () => window.clearInterval(timer);
    }, [verificationPhase, expandedTicketId]);

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
        const selected = tickets.find(ticket => ticket.ticketId === id);
        if (["EXPIRED", "CANCELLED"].includes(selected?.status)) {
            setExpandedTicketId(null);
            setVerificationPhase("idle");
            return;
        }

        setExpandedTicketId(id);
        setVerificationPhase(selected?.status === "USED" ? "used" : "idle");
        setVerificationSeconds(5);
    }

    const ticketDates = [...new Set(tickets.map((ticket) => ticket.startTime ? dateKey(ticket.startTime) : null).filter(Boolean))].sort((a, b) => b.localeCompare(a));
    const filteredTickets = (selectedDate === "ALL" ? tickets : tickets.filter((ticket) => ticket.startTime && dateKey(ticket.startTime) === selectedDate))
        .toSorted((a, b) => (Date.parse(b.startTime) || 0) - (Date.parse(a.startTime) || 0));

    return (
        <section className={styles.page}>
            <h1>내 티켓</h1>
            {nextCursor && <p>최근 발급 티켓부터 표시합니다. 이전 티켓과 날짜는 더 보기로 불러오세요.</p>}
            <div className={styles.panel}>
                <div className={styles.ticketFilterHeader}>
                    <h2>발급된 티켓 <span className={styles.count}>{filteredTickets.length}개</span></h2>
                    <select className={styles.ticketDateFilter} value={selectedDate} onChange={(event) => { setSelectedDate(event.target.value); selectTicket(null); }} aria-label="티켓 날짜 필터">
                        <option value="ALL">전체 날짜</option>
                        {ticketDates.map((date) => <option key={date} value={date}>{date.replaceAll('-', '.')}</option>)}
                    </select>
                </div>
                {error && <p role="alert">{error}</p>}
                {loading || filteredTickets.length === 0 ? null : (
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
                                                    {["EXPIRED", "CANCELLED"].includes(ticket.status) && (
                                                        <div className={styles.ticketCancelledStamp}>{ticket.status}</div>
                                                    )}
                                                    <button
                                                        type="button"
                                                        className={styles.ticketSummary}
                                                        aria-expanded={expanded}
                                                        onClick={() => {
                                                            if (["EXPIRED", "CANCELLED"].includes(ticket.status)) return;
                                                            selectTicket(expandedTicketId === ticket.ticketId ? null : ticket.ticketId);
                                                        }}
                                                        disabled={["EXPIRED", "CANCELLED"].includes(ticket.status)}
                                                    >
                                                        <span className={styles.summaryTop}>
                                                            <span className={styles.summaryStatus} data-status={ticket.status}>{formatTicketStatus(ticket.status)}</span>
                                                            <span className={styles.summaryDate}>{formatDateOnly(ticket.startTime)}</span>
                                                        </span>
                                                        <span className={styles.summaryDetails}>
                                                            <span className={styles.summaryMovieInfo}>
                                                                <strong className={styles.summaryMovie}>{ticket.movieTitle}</strong>
                                                                <span className={styles.summaryTheater}>
                                                                    <InlineDetails items={[ticket.theaterName, ticket.screenName]} />
                                                                </span>
                                                            </span>
                                                            <span className={styles.summaryTime}>
                                                                <span>{formatTime(ticket.startTime)}</span>
                                                                <small>종료 {formatTime(ticket.endTime)}</small>
                                                            </span>
                                                        </span>
                                                    </button>
                                                    <div className={styles.summaryBottom}>
                                                        <span className={styles.summarySeats}><small>좌석</small><strong>{ticket.seats?.join(', ') || '-'}</strong></span>
                                                    {ticket.status === "VALID" && !isShowtimeStarted(ticket) && (
                                                        <button
                                                            type="button"
                                                            className={styles.cancelButton}
                                                            disabled={cancelling}
                                                            onClick={() => cancelTicket(ticket)}
                                                        >
                                                            {cancelling ? "취소 중..." : "예매 취소"}
                                                        </button>
                                                    )}
                                                    </div>
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
            {nextCursor && <button type="button" disabled={loadingMore} onClick={loadMore}>
                {loadingMore ? "불러오는 중…" : "이전 티켓 더 보기"}
            </button>}
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
                            className={styles.ticketModal}
                            ref={modal}
                            tabIndex={-1}
                            role="dialog"
                            aria-modal="true"
                            aria-label="티켓 상세"
                            onClick={(event) => event.stopPropagation()}
                        >
                            <button type="button" className={styles.closeButton} aria-label="티켓 상세 닫기" onClick={() => selectTicket(null)}>×</button>
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
                                        <div className={styles.ticketQrPlaceholder}>
                                            <QRCodeSVG
                                                value={`${TICKET_VERIFY_PAGE_URL}/#/ticket/verify/${encodeURIComponent(selectedTicket.qrCode || selectedTicket.ticketNumber)}`}
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
