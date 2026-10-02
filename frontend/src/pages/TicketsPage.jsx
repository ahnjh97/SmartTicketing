import InlineDetails from '../components/InlineDetails.jsx';
import { useEffect, useState } from "react";
import { ticketApi } from "../api/tickets.js";
import { Link } from 'react-router-dom';
import glass from '../components/GlassButton.module.css';
import RecoveryInbox from '../booking/RecoveryInbox.jsx';
import useAuth from '../hooks/useAuth.js';
import styles from './TicketsPage.module.css';

function formatDate(value) {
    return value ? new Date(value).toLocaleString("ko-KR") : "-";
}

export default function TicketsPage() {
    const { user } = useAuth();
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

    return (
        <section className={styles.page}>
            <h1>내 티켓</h1>
            <div className={styles.panel}><RecoveryInbox key={user?.id}/></div>
            <div className={styles.panel}>
                <h2>발급된 티켓</h2>
                {loading ? <p role="status">티켓을 불러오는 중입니다.</p> : error ? <p role="alert">{error}</p> : tickets.length === 0 ? (
                    <p>발급된 티켓이 없습니다.</p>
                ) : (
                    <div className={styles.tickets}>
                        {tickets.map((ticket) => (
                            <article key={ticket.ticketId} className={styles.ticket}>
                                <h2>{ticket.movieTitle}</h2>
                                <p><InlineDetails items={[ticket.theaterName, ticket.screenName]} /></p>
                                <p>{formatDate(ticket.startTime)} ~ {formatDate(ticket.endTime)}</p>
                                <p>좌석: {ticket.seats?.join(", ") || "-"}</p>
                                <p>티켓 번호: <strong>{ticket.ticketNumber}</strong></p>
                                <p>QR: {ticket.qrCode}</p>
                                <p>상태: {ticket.status}</p>
                                {ticket.groupId && <Link className={glass.button} to={`/booking/restore?group=${ticket.groupId}`}>예약 상세 및 전체 취소</Link>}
                            </article>
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}
