import { request } from "./client.js";

export const ticketApi = {
    mine: () => request("/api/tickets"),
    one: (id) => request(`/api/tickets/${encodeURIComponent(id)}`),
    completeVerify: (qrCode) => request(`/api/tickets/verify/complete/${encodeURIComponent(qrCode)}`, { method: "POST" }),
    issue: (reservationId) => request(`/api/tickets/reservations/${encodeURIComponent(reservationId)}`, {
        method: "POST",
    }),
};
