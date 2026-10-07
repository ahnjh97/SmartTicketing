import { request, apiUrl } from "./client.js";
import { getAccessToken } from "../auth/session.js";

let streamController = null;
const subscribers = new Set();
let reconnectTimer = null;

async function runStream() {
    if (streamController) return;

    const token = getAccessToken();
    if (!token) return;

    const controller = new AbortController();
    streamController = controller;

    try {
        const response = await fetch(apiUrl("/api/notifications/stream"), {
            headers: {
                Accept: "text/event-stream",
                Authorization: `Bearer ${token}`,
            },
            credentials: "include",
            signal: controller.signal,
        });

        if (!response.ok || !response.body) throw new Error(`notification stream failed: ${response.status}`);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!controller.signal.aborted) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            const events = buffer.split("\n\n");
            buffer = events.pop() ?? "";
            for (const event of events) {
                if (event.includes("event: notification")) {
                    subscribers.forEach((listener) => listener());
                }
            }
        }
    } catch {
        // 로그인 종료/구독 해제 시에는 재연결하지 않는다.
    } finally {
        if (streamController === controller) streamController = null;
        if (subscribers.size > 0 && !controller.signal.aborted) {
            reconnectTimer = window.setTimeout(() => {
                reconnectTimer = null;
                runStream();
            }, 3000);
        }
    }
}

function subscribe(listener) {
    subscribers.add(listener);
    runStream();

    return () => {
        subscribers.delete(listener);
        if (subscribers.size === 0) {
            if (reconnectTimer) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            streamController?.abort();
            streamController = null;
        }
    };
}

export const notificationApi = {
    list: (unreadOnly = false, signal) => request("/api/notifications", { query: { unreadOnly }, signal }),
    subscribe,
    read: (id) => request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "PATCH" }),
    readAll: () => request("/api/notifications/read-all", { method: "PATCH" }),
    delete: (id) => request(`/api/notifications/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
