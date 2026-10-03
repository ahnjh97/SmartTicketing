import { request as adminRequest } from './client.js';

export const adminApi = {
    browse: (query, signal) => adminRequest('/api/admin/data/browse', { query, signal }),
    bookingPreview: (body) => adminRequest('/api/admin/bookings/preview', { method: 'POST', body }),
    bookingExecute: (body) => adminRequest('/api/admin/bookings/execute', { method: 'POST', body }),
    summary: (signal) => adminRequest('/api/admin/data/summary', { signal }),
    list: (kind, query, signal) => adminRequest(`/api/admin/data/${kind}`, { query, signal }),
    seats: (id, signal) => adminRequest(`/api/admin/data/showtimes/${id}/seats`, { signal }),
    preview: (scope) => adminRequest('/api/admin/data/preview-delete', { method: 'POST', body: scope }),
    delete: (body) => adminRequest('/api/admin/data/delete', { method: 'POST', body }),
    edit: (kind, id, body) => adminRequest(`/api/admin/data/${kind}/${id}`, { method: 'PATCH', body }),
    collectMovies: (refresh) => adminRequest('/api/admin/data/collect-movies', { method: 'POST', query: { refresh } }),
    collectTheaters: (refresh) => adminRequest('/api/admin/data/collect-theaters', { method: 'POST', query: { refresh } }),
    prepare: () => adminRequest('/api/admin/data/prepare-schedule', { method: 'POST' }),
};
