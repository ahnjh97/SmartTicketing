import { afterEach, expect, test, vi } from 'vitest';
import { adminApi } from '../src/api/admin.js';
import { setAccessToken, clearAccessToken } from '../src/auth/session.js';

afterEach(() => { clearAccessToken(); vi.unstubAllGlobals(); });

test('management requests use the signed-in account token', async () => {
    setAccessToken('signed-in-token');
    const fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, text: async () => '{}' });
    vi.stubGlobal('fetch', fetch);
    await adminApi.collectMovies(true);
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/admin/data/collect-movies?refresh=true'),
        expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ Authorization: 'Bearer signed-in-token' }) }));
});

test('no session cannot send a management mutation', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
    await expect(adminApi.prepare()).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
});
