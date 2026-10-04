import { useEffect, useState } from "react";
import { notificationApi } from "../api/notifications.js";
import "./NotificationsPage.css";

const TYPE_LABELS = {
    SEAT_HOLD_STARTED: "좌석 선점",
    QUEUE_TURN: "대기 순서",
    RESERVATION_COMPLETED: "예매 완료",
    RESERVATION_CANCELLED: "예매 취소",
    PAYMENT_FAILED: "결제 실패",
};

function formatDate(value) {
    return value ? new Date(value).toLocaleString("ko-KR") : "-";
}

export default function NotificationsPage() {
    const [notifications, setNotifications] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");

    useEffect(() => {
        let active = true;
        notificationApi.list(false)
            .then(data => { if (active) setNotifications(Array.isArray(data) ? data : []); })
            .catch(e => { if (active) setError(e?.message ?? "알림을 불러오지 못했습니다."); })
            .finally(() => { if (active) setLoading(false); });
        return () => { active = false; };
    }, []);

    async function markRead(id) {
        try {
            await notificationApi.read(id);
            setNotifications((items) =>
                items.map((item) => item.id === id ? { ...item, read: true } : item)
            );
        } catch (e) {
            setError(e?.message ?? "알림을 읽음 처리하지 못했습니다.");
        }
    }

    async function markAllRead() {
        try {
            await notificationApi.readAll();
            setNotifications((items) => items.map((item) => ({ ...item, read: true })));
        } catch (e) {
            setError(e?.message ?? "알림을 모두 읽음 처리하지 못했습니다.");
        }
    }

    async function remove(id) {
        try {
            await notificationApi.delete(id);
            setNotifications((items) => items.filter((item) => item.id !== id));
        } catch (e) {
            setError(e?.message ?? "알림을 삭제하지 못했습니다.");
        }
    }

    return (
        <section className="page notification-page">
            <div className="card notification-card">
                <div className="notification-page-header">
                    <div>
                        <h1>알림</h1>
                        <p>예매, 좌석 선점, 대기 순서와 결제 상태를 확인할 수 있습니다.</p>
                    </div>
                    <button
                        type="button"
                        className="notification-read-all"
                        onClick={markAllRead}
                        disabled={!notifications.some((item) => !item.read)}
                    >
                        모두 읽음
                    </button>
                </div>

                {error && <p className="error-message">{error}</p>}

                {loading ? (
                    null
                ) : notifications.length === 0 ? (
                    <div className="notification-empty">새로운 알림이 없습니다.</div>
                ) : (
                    <div className="notification-list">
                        {notifications.map((item) => (
                            <article
                                key={item.id}
                                className={`notification-item${item.read ? "" : " is-unread"}`}
                            >
                                <div className="notification-item-main">
                                    <div className="notification-item-title">
                                        <span>{TYPE_LABELS[item.type] ?? "알림"}</span>
                                        {!item.read && <span className="notification-unread-dot" aria-label="읽지 않음" />}
                                    </div>
                                    <p>{item.message}</p>
                                    <time dateTime={item.createdAt}>{formatDate(item.createdAt)}</time>
                                </div>
                                <div className="notification-item-actions">
                                    {!item.read && (
                                        <button type="button" onClick={() => markRead(item.id)}>
                                            읽음
                                        </button>
                                    )}
                                    <button type="button" onClick={() => remove(item.id)}>
                                        삭제
                                    </button>
                                </div>
                            </article>
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}
