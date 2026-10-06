import { request } from './client.js';

// Catalog reads are public; nearby and personal APIs keep their existing authentication.
export const catalog = (path, query, signal) => request(`/api/${path}`, {
    authenticated: false, query, signal,
});

export const bookingApi = {
    createSmartCandidates: (body, key) => request('/api/smart-booking-candidates', { method: 'POST', body, idempotencyKey: key }),
    smartCandidates: (selected, signal) => request('/api/smart-booking-candidates', { query: { selected }, signal }),
    active: signal => request('/api/booking-groups/active', { signal }),
    history: (before, signal) => request('/api/booking-groups/history', { query: { before }, signal }),
    mine: (before, signal) => request('/api/booking-groups', { query: { before }, signal }),
    recovery: (id, signal) => request(`/api/booking-groups/${id}/recovery`, { signal }),
    waiting: (id, signal) => request(`/api/booking-groups/${id}/waiting-queues`, { signal }),
    registerWaiting: (id, showtimeIds, key, seatZone, seatIds) => request(`/api/booking-groups/${id}/waiting-queues`, {
        method: 'POST', body: { showtimeIds, ...(seatZone ? { seatZone } : {}), ...(seatIds ? { seatIds } : {}) }, idempotencyKey: key,
    }),
    cancelGroup: (id, key) => request(`/api/booking-groups/${id}/cancel`, { method: 'POST', idempotencyKey: key }),
    createGroup: (body, key) => request('/api/booking-groups', { method: 'POST', body, idempotencyKey: key }),
    group: (id, signal) => request(`/api/booking-groups/${id}`, { signal }),
    hold: (id, seatIds, key) => request(`/api/booking-groups/${id}/manual-hold`, {
        method: 'POST', body: { seatIds }, idempotencyKey: key,
    }),
    smartHold: (id, key) => request(`/api/booking-groups/${id}/smart-hold`, { method: 'POST', idempotencyKey: key }),
    reservation: (id, signal) => request(`/api/reservations/${id}`, { signal }),
    payment: (id, signal) => request(`/api/reservations/${id}/payment`, { signal }),
    pay: (id, key, simulateFailure = false) => request(`/api/reservations/${id}/mock-payments`, {
        method: 'POST', body: { paymentMethod: 'MOCK', simulateFailure }, idempotencyKey: key,
    }),
    cancel: (id, key) => request(`/api/reservations/${id}/cancel`, { method: 'POST', idempotencyKey: key }),
};
