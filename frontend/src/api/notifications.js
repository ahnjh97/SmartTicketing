import { request, apiUrl } from "./client.js";
import { getAccessToken } from "../auth/session.js";

let streamController = null;
const subscribers = new Set();
let reconnectTimer = null;

function describeEvent(event) {
    const normalized = event.replace(/\r/g, "");
    const eventName = normalized.match(/^event:\s*(.*)$/m)?.[1]?.trim() ?? null;
    const dataLines = normalized
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart());

    return {
        raw: event,
        normalized,
        eventName,
        data: dataLines.join("\n"),
        // Reconcile DB state after reconnect too: Pub/Sub invalidations are not durable.
        isNotification: eventName === "notification" || eventName === "connected",
    };
}

async function runStream() {
    if (streamController) {
        console.log("[NOTIFICATION SSE] stream already active");
        return;
    }

    const token = getAccessToken();
    console.log("[NOTIFICATION SSE] token check", {
        hasToken: Boolean(token),
        tokenLength: token?.length ?? 0,
    });

    if (!token) {
        console.warn("[NOTIFICATION SSE] no access token - stream not started");
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
            if (response.status === 429 || response.status === 503) {
                const data = await response.json().catch(() => null);
                if (data?.code === 'ADMISSION_REQUIRED' || data?.code === 'ADMISSION_UNAVAILABLE')
                    window.dispatchEvent(new Event('admission-required'));
            }
            throw new Error(`notification stream failed: ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        console.log("[NOTIFICATION SSE] stream reader ready");

        while (!controller.signal.aborted) {
            const { value, done } = await reader.read();

            console.log("[NOTIFICATION SSE] chunk received", {
                done,
                bytes: value?.length ?? 0,
            });

            if (done) break;

            const decoded = decoder.decode(value, { stream: true });
            buffer += decoded;

            console.log("[NOTIFICATION SSE] decoded chunk", JSON.stringify(decoded));
            console.log("[NOTIFICATION SSE] buffer before parse", JSON.stringify(buffer));

            const events = buffer.split(/\r?\n\r?\n/);
            buffer = events.pop() ?? "";

            console.log("[NOTIFICATION SSE] parsed event count", events.length);
            console.log("[NOTIFICATION SSE] remaining buffer", JSON.stringify(buffer));

            for (const event of events) {
                const parsed = describeEvent(event);

                console.log("[NOTIFICATION SSE] parsed event", parsed);

                if (parsed.isNotification) {
                    console.log("[NOTIFICATION SSE] >>> NOTIFICATION EVENT DETECTED <<<", {
                        eventName: parsed.eventName,
                        data: parsed.data,
                        subscriberCount: subscribers.size,
                    });

                    subscribers.forEach((listener, index) => {
                        console.log("[NOTIFICATION SSE] notifying subscriber", {
                            index,
                            subscriberCount: subscribers.size,
                        });

                        try {
                            listener();
                            console.log("[NOTIFICATION SSE] subscriber callback completed", { index });
                        } catch (error) {
                            console.error("[NOTIFICATION SSE] subscriber callback failed", {
                                index,
                                error,
                            });
                        }
                    });
                } else {
                    console.log("[NOTIFICATION SSE] non-notification event ignored", {
                        eventName: parsed.eventName,
                        data: parsed.data,
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
    page: (cursor, size = 20, unreadOnly = false) => request("/api/notifications/page", { query: { cursor, size, unreadOnly } }),
    subscribe,
    read: (id) => request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "PATCH" }),
    readAll: () => request("/api/notifications/read-all", { method: "PATCH" }),
    delete: (id) => request(`/api/notifications/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
