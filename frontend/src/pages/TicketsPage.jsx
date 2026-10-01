import { useEffect, useState } from "react";
import { ticketApi } from "../api/tickets.js";

function formatDate(value) {
    return value ? new Date(value).toLocaleString("ko-KR") : "-";
}

export default function TicketsPage() {
    const [tickets, setTickets] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

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

    if (loading) return <section className="page"><div className="card">티켓을 불러오는 중입니다.</div></section>;
    if (error) return <section className="page"><div className="card"><p className="error-message">{error}</p></div></section>;

    return (
        <section className="page">
            <div className="card">
                <h1>내 티켓</h1>
                {tickets.length === 0 ? (
                    <p>발급된 티켓이 없습니다.</p>
                ) : (
                    <div style={{ display: "grid", gap: 16 }}>
                        {tickets.map((ticket) => (
                            <article key={ticket.ticketId} className="card">
                                <h2>{ticket.movieTitle}</h2>
                                <p>{ticket.theaterName} · {ticket.screenName}</p>
                                <p>{formatDate(ticket.startTime)} ~ {formatDate(ticket.endTime)}</p>
                                <p>좌석: {ticket.seats?.join(", ") || "-"}</p>
                                <p>티켓 번호: <strong>{ticket.ticketNumber}</strong></p>
                                <p>QR: {ticket.qrCode}</p>
                                <p>상태: {ticket.status}</p>
                            </article>
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}
