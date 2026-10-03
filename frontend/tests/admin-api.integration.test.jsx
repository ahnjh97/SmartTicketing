import { afterEach, expect, test, vi } from 'vitest';
import { adminApi } from '../src/api/admin.js';
import { setAccessToken, clearAccessToken } from '../src/auth/session.js';

afterEach(() => { clearAccessToken(); vi.unstubAllGlobals(); vi.useRealTimers(); });

test('long mutations return their result after polling the accepted job', async () => {
    vi.useFakeTimers(); setAccessToken('signed-in-token');
    const fetch = vi.fn()
        .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({ id: 'task-1', state: 'RUNNING' }) })
        .mockResolvedValueOnce({ ok: true, status: 200, text: async () => JSON.stringify({ id: 'task-1', state: 'COMPLETED', result: { targetCount: 67 } }) });
    vi.stubGlobal('fetch', fetch);
    const result = adminApi.delete({ scope: { kind: 'theaters', mode: 'all' } });
    await vi.advanceTimersByTimeAsync(1500);
    await expect(result).resolves.toEqual({ targetCount: 67 });
    expect(fetch).toHaveBeenLastCalledWith(expect.stringContaining('/api/admin/data/task?id=task-1'), expect.objectContaining({ method: 'GET' }));
    expect(fetch.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(1);
});

test('management requests use the signed-in account token', async () => {
    setAccessToken('signed-in-token');
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{}' });
    vi.stubGlobal('fetch', fetch);
    await adminApi.collectMovies();
    expect(fetch).toHaveBeenCalledWith(expect.stringMatching(/\/api\/admin\/data\/collect-movies$/),
        expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer signed-in-token' }) }));
});

test('no session cannot send a management mutation', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    await expect(adminApi.prepare()).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
});
