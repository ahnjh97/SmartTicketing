import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { ticketApi } from "../api/tickets.js";
import styles from "./TicketVerifyPage.module.css";

export default function TicketVerifyPage() {
    const { qrCode } = useParams();
    const [phase, setPhase] = useState("loading");
    const [message, setMessage] = useState("티켓을 확인하고 있습니다.");
    const [ticket, setTicket] = useState(null);

    useEffect(() => {
        let cancelled = false;
        let timer;

        const run = async () => {
            try {
                if (!qrCode) throw new Error("QR 코드가 없습니다.");

                const started = await ticketApi.verify(qrCode);
                if (cancelled) return;

                setTicket(started.ticket ?? null);

                if (started.used) {
                    setPhase("used");
                    setMessage("티켓 사용처리가 되었습니다.");
                    return;
                }

                if (!started.processing) {
                    setPhase("error");
                    setMessage(started.message || "사용할 수 없는 티켓입니다.");
                    return;
                }

                setPhase("processing");
                setMessage("처리중입니다...");

                const poll = async () => {
                    try {
                        const result = await ticketApi.verifyStatus(qrCode);
                        if (cancelled) return;

                        setTicket(result.ticket ?? null);

                        if (result.used) {
                            setPhase("used");
                            setMessage("티켓 사용처리가 되었습니다.");
                            return;
                        }

                        if (!result.processing) {
                            setPhase("error");
                            setMessage(result.message || "티켓 사용 처리에 실패했습니다.");
                            return;
                        }

                        timer = window.setTimeout(poll, 1000);
                    } catch (error) {
                        if (cancelled) return;
                        setPhase("error");
                        setMessage(error?.message || "티켓 확인 중 오류가 발생했습니다.");
                    }
                };

                timer = window.setTimeout(poll, 1000);
            } catch (error) {
                if (cancelled) return;
                setPhase("error");
                setMessage(error?.message || "티켓을 확인할 수 없습니다.");
            }
        };

        run();

        return () => {
            cancelled = true;
            if (timer) window.clearTimeout(timer);
        };
    }, [qrCode]);

    return (
        <main className={styles.page} aria-live="polite">
            <section className={styles.card}>
                <div className={`${styles.icon} ${styles[phase] ?? ""}`}>
                    {phase === "used" ? "✓" : phase === "error" ? "!" : "T"}
                </div>

                <p className={styles.brand}>SMART TICKETING</p>

                <h1>
                    {phase === "used"
                        ? "티켓 사용 완료"
                        : phase === "processing"
                            ? "티켓 확인 중"
                            : phase === "error"
                                ? "티켓 확인 실패"
                                : "티켓 확인 중"}
                </h1>

                <p className={styles.message}>{message}</p>

                {phase === "processing" && (
                    <div className={styles.progress} aria-label="티켓 사용 처리 중" />
                )}

                {ticket && (
                    <div className={styles.ticketInfo}>
                        <strong>{ticket.movieTitle}</strong>
                        <span>{ticket.theaterName} · {ticket.screenName}</span>
                        <span>티켓 번호 {ticket.ticketNumber}</span>
                    </div>
                )}

                {phase === "used" && (
                    <p className={styles.success}>이 티켓은 정상적으로 사용 처리되었습니다.</p>
                )}
            </section>
        </main>
    );
}
