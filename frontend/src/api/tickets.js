import { request } from "./client.js";

export const ticketApi = {
    mine: () => request("/api/tickets"),
    one: (id) => request(`/api/tickets/${encodeURIComponent(id)}`),
    issue: (reservationId) => request(`/api/tickets/reservations/${encodeURIComponent(reservationId)}`, {
        method: "POST",
    }),
};
