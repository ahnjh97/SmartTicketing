import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import "./TicketVerifyPage.css";

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

async function requestVerify(path, options = {}) {
    const response = await fetch(`${API_BASE_URL}${path}`, options);
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || "티켓 확인에 실패했습니다.");
    return data;
}

export default function TicketVerifyPage() {
    const { qrCode } = useParams();
    const [phase, setPhase] = useState("loading");
    const [ticket, setTicket] = useState(null);
    const [message, setMessage] = useState("");
    const [seconds, setSeconds] = useState(5);

    useEffect(() => {
        let cancelled = false;
        let interval;

        const start = async () => {
            try {
                const data = await requestVerify(`/api/tickets/verify/${encodeURIComponent(qrCode || "")}`, {
                    method: "POST",
                });
                if (cancelled) return;

                setTicket(data.ticket || null);
                setMessage(data.message || "");

                if (!data.processing) {
                    setPhase(data.used ? "used" : "failed");
                    return;
                }

                setPhase("processing");
                setSeconds(5);

                interval = window.setInterval(async () => {
                    try {
                        const status = await requestVerify(`/api/tickets/verify/${encodeURIComponent(qrCode || "")}`);
                        if (cancelled) return;
                        setTicket(status.ticket || null);
                        setMessage(status.message || "");

                        if (status.used) {
                            window.clearInterval(interval);
                            setSeconds(0);
                            setPhase("used");
                        }
                    } catch (error) {
                        if (!cancelled) {
                            window.clearInterval(interval);
                            setMessage(error.message || "티켓 확인에 실패했습니다.");
                            setPhase("failed");
                        }
                    }
                }, 1000);
            } catch (error) {
                if (!cancelled) {
                    setMessage(error.message || "티켓 확인에 실패했습니다.");
                    setPhase("failed");
                }
            }
        };

        start();

        return () => {
            cancelled = true;
            if (interval) window.clearInterval(interval);
        };
    }, [qrCode]);

    useEffect(() => {
        if (phase !== "processing") return;

        const timer = window.setInterval(() => {
            setSeconds((current) => Math.max(0, current - 1));
        }, 1000);

        return () => window.clearInterval(timer);
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
                        <strong>처리 중입니다...</strong>
                        <span>{seconds}초 후 사용 완료</span>
                    </div>
                )}

                {phase === "used" && <div className="used-stamp">USED</div>}
                {phase === "used" && <p className="verify-message">사용 처리되었습니다.</p>}
                {phase === "failed" && <p className="verify-error">{message}</p>}
            </section>
        </main>
    );
}
