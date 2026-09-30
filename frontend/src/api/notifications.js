import { request } from "./client.js";

export const notificationApi = {
    list: (unreadOnly = false) => request("/api/notifications", { query: { unreadOnly } }),
    read: (id) => request(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "PATCH" }),
    readAll: () => request("/api/notifications/read-all", { method: "PATCH" }),
    delete: (id) => request(`/api/notifications/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
