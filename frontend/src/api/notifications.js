import { request } from "./client.js";

export const notificationApi = {
    list: (unreadOnly = false, signal) => request("/api/notifications", { query: { unreadOnly }, signal }),
    read: (id) => request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "PATCH" }),
    readAll: () => request("/api/notifications/read-all", { method: "PATCH" }),
    delete: (id) => request(`/api/notifications/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
