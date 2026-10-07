import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, readdir, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname, basename, resolve } from 'node:path';
import { normalize, sample, summarize, runBench, request, bodyFor } from './run.mjs';

async function fixture(t, mode = '') {
    const directory = await mkdtemp(join(tmpdir(), 'smart-bench-test-'));
    const calls = [], keys = new Map(), active = new Map();
    let next = 1, dropped = false;
    const server = createServer(async (req, res) => {
        let text = ''; for await (const chunk of req) text += chunk;
        const body = text ? JSON.parse(text) : undefined;
        const key = req.headers['idempotency-key'];
        const user = Number(req.headers.authorization?.replace('Bearer token-', '') ?? 1);
        const path = req.url;
        calls.push({ path, key, body, user });
        const send = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
        if (path === '/api/auth/login') return send(200, { accessToken: `token-${body.loginId}` });
        if (path === '/api/users/me') return send(200, { id: user,
            preferredTheaters: [{ theaterId: 1, priority: 0 }], preferredSeats: [{ position: 'MIDDLE_MIDDLE', priority: 0 }] });
        if (path === '/api/booking-groups/active') return send(200, mode === 'existing' ? [{ id: 999 }]
            : [...active].filter(([, owner]) => owner === user).map(([id]) => ({ id })));
        if (req.method === 'POST' && path === '/api/smart-booking-candidates') {
            if (mode === 'reject') return send(409, { code: 'NO_CANDIDATES' });
            if (!keys.has(key)) { keys.set(key, next); active.set(next++, user); }
            if (mode === 'lost-response' && !dropped) { dropped = true; req.socket.destroy(); return; }
            return send(201, { groupIds: [keys.get(key)] });
        }
        if (req.method === 'GET' && path === '/api/smart-booking-candidates') {
            if (mode === 'read-failure') return send(500, {});
            return send(200, { candidates: [...active].filter(([, owner]) => owner === user).map(([id]) => ({
                groupId: id, kind: 'PREFERRED', zone: 'MIDDLE_MIDDLE', status: 'HOLDING',
                payment: { reservation: { status: 'PENDING' } }, waiting: { items: [] },
            })) });
        }
        const cancel = path.match(/^\/api\/booking-groups\/(\d+)\/cancel$/);
        if (cancel) {
            if (mode === 'cleanup-failure') return send(503, {});
            active.delete(Number(cancel[1])); return send(200, {});
        }
        send(404, {});
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(async () => {
        server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
        assert.equal(dirname(resolve(directory)), resolve(tmpdir()));
        assert.ok(basename(directory).startsWith('smart-bench-test-'));
        await rm(directory, { recursive: true, force: true });
    });
    const config = normalize({ baseUrl: `http://127.0.0.1:${server.address().port}`, movieId: 1,
        viewingDate: 'tomorrow', partySize: 2, ranges: [{ name: '1h', from: '10:00', to: '11:00' }],
        rounds: 1, iterations: 1, warmupIterations: 0, pauseMs: 0, timeoutMs: 2000 });
    return { directory, config, calls, active, keys };
}

test('validates time boundaries, duplicate cases, and Korean tomorrow', () => {
    const raw = { baseUrl: 'http://localhost:8080', movieId: 1, partySize: 2,
        viewingDate: 'tomorrow', ranges: [{ name: 'day', from: '06:00', to: '05:59' }] };
    assert.equal(normalize(raw, new Date('2026-10-07T23:00:00Z')).viewingDate, '2026-10-09');
    assert.throws(() => normalize({ ...raw, concurrency: [1, 1] }));
    assert.throws(() => normalize({ ...raw, ranges: [{ name: 'bad', from: '10:00', to: '10:00' }] }));
    assert.throws(() => normalize({ ...raw, viewingDate: '2027-02-30' }));
});

test('fast rejection does not improve successful latency percentiles; warmup excluded', () => {
    const base = { range: '1h', concurrency: 1, cleanupOk: true };
    const [s] = summarize([{ ...base, createOk: true, readOk: true, createMs: 100, readMs: 50, flowMs: 150 },
        { ...base, createOk: false, readOk: false, createMs: 1 },
        { ...base, warmup: true, createOk: true, readOk: true, flowMs: 10000 }]);
    assert.equal(s.attempts, 2); assert.equal(s.failures, 1); assert.equal(s.flow.p95, 150);
    assert.equal(s.failedCreate.p95, 1);
});

test('multiple accounts generate separate requests, reports exclude credentials, and cleanup is scoped', async t => {
    const f = await fixture(t);
    f.config.concurrency = [2]; f.config.iterations = 2;
    const summary = await runBench(f.config, [{ loginId: '1', password: 'never-log-me' }, { loginId: '2', password: 'never-log-me' }], f.directory, { log() {} });
    assert.equal(summary[0].successes, 4); assert.equal(f.keys.size, 4); assert.equal(f.active.size, 0);
    const manifest = await readFile(join(f.directory, 'manifest.json'), 'utf8');
    assert.ok(!manifest.includes('never-log-me') && !manifest.includes('token-'));
    assert.equal((await readdir(join(f.directory, 'journal'))).length, 4);
});

test('existing active bookings stop the benchmark without any creation or cancellation', async t => {
    const f = await fixture(t, 'existing');
    await assert.rejects(runBench(f.config, [{ loginId: '1', password: 'x' }], f.directory, { log() {} }), /기존/);
    assert.equal(f.keys.size, 0); assert.ok(!f.calls.some(c => c.path.endsWith('/cancel')));
});

test('lost creation response is replayed with exactly the same key/body and cleaned; sample remains failed', async t => {
    const f = await fixture(t, 'lost-response');
    const row = await sample(f.config, { id: 1, token: 'token-1' }, f.config.ranges[0], {}, f.directory);
    assert.equal(row.createOk, false); assert.equal(row.cleanupOk, true); assert.equal(f.active.size, 0);
    const posts = f.calls.filter(c => c.key && c.path === '/api/smart-booking-candidates');
    assert.equal(posts.length, 2); assert.equal(posts[0].key, posts[1].key); assert.deepEqual(posts[0].body, posts[1].body);
});

test('failed first GET still cancels successful creations', async t => {
    const f = await fixture(t, 'read-failure');
    const row = await sample(f.config, { id: 1, token: 'token-1' }, f.config.ranges[0], {}, f.directory);
    assert.equal(row.createOk, true); assert.equal(row.readOk, false); assert.equal(row.cleanupOk, true);
    assert.equal(f.active.size, 0);
});

test('cleanup failure stops further load and retains recoverable journal and report', async t => {
    const f = await fixture(t, 'cleanup-failure'); f.config.iterations = 3;
    await assert.rejects(runBench(f.config, [{ loginId: '1', password: 'x' }], f.directory, { log() {} }), /정리 미완료/);
    assert.equal(f.keys.size, 1);
    const [file] = await readdir(join(f.directory, 'journal'));
    const record = JSON.parse(await readFile(join(f.directory, 'journal', file), 'utf8'));
    assert.equal(record.cleaned, false); assert.equal(record.groupIds.length, 1);
    const summary = JSON.parse(await readFile(join(f.directory, 'summary.json'), 'utf8'));
    assert.equal(summary.summary[0].cleanupFailures, 1);
});

test('recovery replays an interrupted journal and leaves unrelated active bookings untouched', async t => {
    const f = await fixture(t);
    const record = { key: 'interrupted-request-1234', userId: 1, body: bodyFor(f.config, f.config.ranges[0]), uncertain: true, cleaned: false };
    await request(f.config, '/api/smart-booking-candidates', 'token-1', record.body, record.key);
    f.active.set(999, 1);
    await mkdir(join(f.directory, 'journal'));
    await writeFile(join(f.directory, 'journal', `${record.key}.json`), JSON.stringify(record));
    await runBench(f.config, [{ loginId: '1', password: 'x' }], f.directory, { recover: true, log() {} });
    assert.equal(f.keys.size, 1);
    assert.deepEqual([...f.active.keys()], [999]);
    const saved = JSON.parse(await readFile(join(f.directory, 'journal', `${record.key}.json`), 'utf8'));
    assert.equal(saved.cleaned, true);
});

test('duplicate users are rejected before creation', async t => {
    const f = await fixture(t); f.config.concurrency = [2];
    await assert.rejects(runBench(f.config, [{ loginId: '1', password: 'x' }, { loginId: '1', password: 'x' }], f.directory, { log() {} }), /서로 다른/);
    assert.equal(f.keys.size, 0);
});

test('a business rejection is counted as failure without replaying or cancelling anything', async t => {
    const f = await fixture(t, 'reject');
    const row = await sample(f.config, { id: 1, token: 'token-1' }, f.config.ranges[0], {}, f.directory);
    assert.equal(row.createStatus, 409); assert.equal(row.createOk, false); assert.equal(row.cleanupOk, true);
    assert.equal(f.calls.length, 1); assert.equal(f.keys.size, 0);
});
