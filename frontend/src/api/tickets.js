import { request } from "./client.js";

export const ticketApi = {
    mine: () => request("/api/tickets"),
    one: (id) => request(`/api/tickets/${encodeURIComponent(id)}`),
    completeVerify: (qrCode) => request(`/api/tickets/verify/complete/${encodeURIComponent(qrCode)}`, { method: "POST" }),
    verify: (qrCode) => request(`/api/tickets/verify/${encodeURIComponent(qrCode)}`, { method: "POST", authenticated: false }),
    verifyStatus: (qrCode) => request(`/api/tickets/verify/status/${encodeURIComponent(qrCode)}`, { authenticated: false }),
    cancelReservation: (reservationId, idempotencyKey) => request(`/api/reservations/${encodeURIComponent(reservationId)}/cancel`, {
        method: "POST",
        idempotencyKey,
    }),
    issue: (reservationId) => request(`/api/tickets/reservations/${encodeURIComponent(reservationId)}`, {
        method: "POST",
    }),
};
