import { request } from './client.js';

// Catalog reads are public; nearby and personal APIs keep their existing authentication.
export const catalog = (path, query, signal) => request(`/api/${path}`, {
    authenticated: false, query, signal,
});
