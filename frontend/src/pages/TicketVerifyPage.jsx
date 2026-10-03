import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import "./TicketVerifyPage.css";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export default function TicketVerifyPage() {
    const { qrCode } = useParams();
    const [phase, setPhase] = useState("loading");
    const [ticket, setTicket] = useState(null);
    const [message, setMessage] = useState("");
    const [seconds, setSeconds] = useState(5);

    useEffect(() => {
        const controller = new AbortController();

        fetch(`${API_BASE_URL}/api/tickets/verify/${encodeURIComponent(qrCode || "")}`, {
            method: "POST",
            signal: controller.signal,
        })
            .then(async (response) => {
                const data = await response.json().catch(() => ({}));
                if (!response.ok) throw new Error(data.message || "티켓 확인에 실패했습니다.");
                return data;
            })
            .then((data) => {
                setTicket(data.ticket || null);
                setMessage(data.message || "");
                setPhase(data.used ? "processing" : "failed");
            })
            .catch((error) => {
                if (error.name !== "AbortError") {
                    setMessage(error.message || "티켓 확인에 실패했습니다.");
                    setPhase("failed");
                }
            });

        return () => controller.abort();
    }, [qrCode]);

    useEffect(() => {
        if (phase !== "processing") return;

        const interval = window.setInterval(() => {
            setSeconds((current) => {
                if (current <= 1) {
                    window.clearInterval(interval);
                    setPhase("used");
                    return 0;
                }
                return current - 1;
            });
        }, 1000);

        return () => window.clearInterval(interval);
    }, [phase]);

    useEffect(() => {
        if (phase !== "used") return;

        const timer = window.setTimeout(() => {
            window.close();
            if (!window.closed) window.history.back();
        }, 1200);

        return () => window.clearTimeout(timer);
    }, [phase]);

    return (
        <main className="ticket-verify-page">
            <section className="verify-ticket">
                <header className="verify-header">
                    <span>CINEMA PASS</span>
                    <strong>{phase === "used" ? "USED" : "TICKET VERIFY"}</strong>
                </header>

                <div className="verify-content">
                    <div>
                        <span className="verify-label">MOVIE</span>
                        <h1>{ticket?.movieTitle || "티켓 확인"}</h1>
                        <div className="verify-info">
                            <p><span>THEATER</span>{ticket?.theaterName || "-"}</p>
                            <p><span>SEAT</span>{ticket?.seats?.join(", ") || "-"}</p>
                            <p><span>TICKET NO.</span>{ticket?.ticketNumber || "-"}</p>
                        </div>
                    </div>

                    <div className="verify-qr-area">
                        <div className="verify-qr-placeholder">QR</div>
                    </div>
                </div>

                {phase === "loading" && <div className="verify-overlay"><strong>티켓 확인 중입니다.</strong></div>}

                {phase === "processing" && (
                    <div className="verify-overlay processing">
                        <div className="processing-spinner" />
                        <strong>처리 중입니다.</strong>
                        <span>{seconds}초 후 사용 완료</span>
                    </div>
                )}

                {phase === "used" && (
                    <div className="used-stamp">USED</div>
                )}

                {phase === "used" && <p className="verify-message">사용 처리되었습니다.</p>}
                {phase === "failed" && <p className="verify-error">{message}</p>}
            </section>
        </main>
    );
}
