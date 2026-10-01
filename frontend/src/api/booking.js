import { request } from './client.js';

// Catalog reads are public; nearby and personal APIs keep their existing authentication.
export const catalog = (path, query, signal) => request(`/api/${path}`, {
    authenticated: false, query, signal,
});

export const bookingApi = {
    createGroup: (body, key) => request('/api/booking-groups', { method: 'POST', body, idempotencyKey: key }),
    group: (id, signal) => request(`/api/booking-groups/${id}`, { signal }),
    hold: (id, seatIds, key) => request(`/api/booking-groups/${id}/manual-hold`, {
        method: 'POST', body: { seatIds }, idempotencyKey: key,
    }),
    reservation: (id, signal) => request(`/api/reservations/${id}`, { signal }),
    payment: (id, signal) => request(`/api/reservations/${id}/payment`, { signal }),
    pay: (id, key, simulateFailure = false) => request(`/api/reservations/${id}/mock-payments`, {
        method: 'POST', body: { paymentMethod: 'MOCK', simulateFailure }, idempotencyKey: key,
    }),
    cancel: (id, key) => request(`/api/reservations/${id}/cancel`, { method: 'POST', idempotencyKey: key }),
};
