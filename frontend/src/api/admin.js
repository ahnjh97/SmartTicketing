import { request as adminRequest } from './client.js';

async function taskRequest(path, options) {
    let task = await adminRequest(path, options);
    if (!task?.id || !task?.state) return task;
    const id = task.id;
    window.dispatchEvent(new CustomEvent('admin-task', { detail: task }));
    while (task.state === 'RUNNING') {
        await new Promise(resolve => setTimeout(resolve, 1500));
        task = await adminRequest('/api/admin/data/task', { query: { id } });
        window.dispatchEvent(new CustomEvent('admin-task', { detail: task }));
    }
    if (task.state !== 'COMPLETED') throw new Error(task.detail || '작업이 중단되었습니다. 남은 데이터를 다시 확인해주세요.');
    return task.result;
}

export const adminApi = {
    task: (signal) => adminRequest('/api/admin/data/task', { signal }),
    dates: (query, signal) => adminRequest('/api/admin/data/dates', { query, signal }),
    browse: (query, signal) => adminRequest('/api/admin/data/browse', { query, signal }),
    bookingPreview: (body) => adminRequest('/api/admin/bookings/preview', { method: 'POST', body }),
    bookingExecute: (body) => taskRequest('/api/admin/bookings/execute', { method: 'POST', body }),
    collectionStatus: (signal) => adminRequest('/api/admin/data/collection-status', { signal }),
    summary: (signal) => adminRequest('/api/admin/data/summary', { signal }),
    list: (kind, query, signal) => adminRequest(`/api/admin/data/${kind}`, { query, signal }),
    seats: (id, signal) => adminRequest(`/api/admin/data/showtimes/${id}/seats`, { signal }),
    preview: (scope) => adminRequest('/api/admin/data/preview-delete', { method: 'POST', body: scope }),
    delete: (body) => taskRequest('/api/admin/data/delete', { method: 'POST', body }),
    collectMovies: () => taskRequest('/api/admin/data/collect-movies', { method: 'POST' }),
    collectTheaters: () => taskRequest('/api/admin/data/collect-theaters', { method: 'POST' }),
    prepare: () => taskRequest('/api/admin/data/prepare-schedule', { method: 'POST' }),
};
