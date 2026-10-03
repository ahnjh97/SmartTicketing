import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";

export default function TicketVerifyPage() {
    const { qrCode } = useParams();
    const [message, setMessage] = useState("티켓을 확인하는 중입니다.");
    const [error, setError] = useState(false);

    useEffect(() => {
        if (!qrCode) {
            setError(true);
            setMessage("유효하지 않은 티켓입니다.");
            return;
        }

        // 실제 USED 처리는 Spring API가 담당합니다.
        // 배포 URL에서도 API 주소를 사용할 수 있도록 VITE_API_BASE_URL을 지원합니다.
        const apiBase = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
        const controller = new AbortController();

        fetch(`${apiBase}/api/tickets/verify/${encodeURIComponent(qrCode)}`, {
            method: "POST",
            credentials: "include",
            signal: controller.signal,
        })
            .then(async (response) => {
                const data = await response.json().catch(() => ({}));
                if (!response.ok) {
                    throw new Error(data.message || "티켓을 사용할 수 없습니다.");
                }
                return data;
            })
            .then(() => {
                setMessage("사용 처리되었습니다.");
            })
            .catch((e) => {
                if (e.name === "AbortError") return;
                setError(true);
                setMessage(e.message || "티켓 확인에 실패했습니다.");
            });

        return () => controller.abort();
    }, [qrCode]);

    useEffect(() => {
        if (error || message !== "사용 처리되었습니다.") return;

        const timer = window.setTimeout(() => {
            window.close();
            window.location.replace("about:blank");
        }, 5000);

        return () => window.clearTimeout(timer);
    }, [error, message]);

    return (
        <main style={{
            minHeight: "100vh",
            display: "grid",
            placeItems: "center",
            padding: "24px",
            boxSizing: "border-box",
            background: "#f5f5f5",
        }}>
            <section style={{
                width: "min(420px, 100%)",
                padding: "36px 28px",
                borderRadius: "16px",
                background: "#fff",
                boxShadow: "0 10px 30px rgba(0,0,0,.08)",
                textAlign: "center",
            }}>
                <h1>{error ? "티켓 확인 실패" : "티켓 확인"}</h1>
                <p>{message}</p>
                {!error && message === "사용 처리되었습니다." && (
                    <p>5초 후 페이지가 종료됩니다.</p>
                )}
            </section>
        </main>
    );
}
