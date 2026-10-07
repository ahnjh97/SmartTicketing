import { request, apiUrl } from "./client.js";
import { getAccessToken } from "../auth/session.js";

let streamController = null;
const subscribers = new Set();
let reconnectTimer = null;

async function runStream() {
    if (streamController) {
        console.log("[NOTIFICATION SSE] stream already active");
        return;
    }

    const token = getAccessToken();
    if (!token) {
        console.log("[NOTIFICATION SSE] no access token");
        return;
    }

    const controller = new AbortController();
    streamController = controller;

    console.log("[NOTIFICATION SSE] connecting");

    try {
        const url = apiUrl("/api/notifications/stream");
        console.log("[NOTIFICATION SSE] request", url);

        const response = await fetch(url, {
            headers: {
                Accept: "text/event-stream",
                Authorization: `Bearer ${token}`,
            },
            credentials: "include",
            signal: controller.signal,
        });

        console.log("[NOTIFICATION SSE] response", {
            status: response.status,
            ok: response.ok,
            contentType: response.headers.get("content-type"),
        });

        if (!response.ok || !response.body) {
            throw new Error(`notification stream failed: ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!controller.signal.aborted) {
            const { value, done } = await reader.read();

            console.log("[NOTIFICATION SSE] chunk received", {
                done,
                bytes: value?.length ?? 0,
            });

            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            console.log("[NOTIFICATION SSE] buffer", buffer);

            const events = buffer.split(/\r?\n\r?\n/);
            buffer = events.pop() ?? "";

            for (const event of events) {
                console.log("[NOTIFICATION SSE] event", event);

                if (event.includes("event: notification")) {
                    console.log("[NOTIFICATION SSE] notification event detected");
                    subscribers.forEach((listener) => {
                        console.log("[NOTIFICATION SSE] notifying subscriber");
                        listener();
                    });
                }
            }
        }
    } catch (error) {
        if (!controller.signal.aborted) {
            console.error("[NOTIFICATION SSE] stream error", error);
        } else {
            console.log("[NOTIFICATION SSE] stream aborted");
        }
    } finally {
        if (streamController === controller) streamController = null;

        if (subscribers.size > 0 && !controller.signal.aborted) {
            console.log("[NOTIFICATION SSE] reconnect scheduled");
            reconnectTimer = window.setTimeout(() => {
                reconnectTimer = null;
                runStream();
            }, 3000);
        }
    }
}

function subscribe(listener) {
    subscribers.add(listener);
    console.log("[NOTIFICATION SSE] subscriber added", {
        subscribers: subscribers.size,
    });

    runStream();

    return () => {
        subscribers.delete(listener);
        console.log("[NOTIFICATION SSE] subscriber removed", {
            subscribers: subscribers.size,
        });

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
    delete: (id) => request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "DELETE" }),
};
