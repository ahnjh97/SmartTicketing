import { readFile, writeFile, appendFile, mkdir, readdir, rename } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';

const endpoint = '/api/smart-booking-candidates';
const activePath = '/api/booking-groups/active';
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const ok = response => response.status >= 200 && response.status < 300;
const idsFrom = response => ok(response) && Array.isArray(response.data?.groupIds)
    && response.data.groupIds.length > 0 && response.data.groupIds.every(positiveId)
    ? [...new Set(response.data.groupIds)] : null;
const json = value => JSON.stringify(value, null, 2) + '\n';
const assert = (condition, message) => { if (!condition) throw new Error(message); };

export function normalize(raw, now = new Date()) {
    const config = { concurrency: [1], rounds: 3, iterations: 10, warmupIterations: 1,
        timeoutMs: 60000, pauseMs: 500, label: 'baseline', ...raw };
    const url = new URL(config.baseUrl);
    assert(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
        && url.pathname === '/' && !url.search && !url.hash, 'baseUrl은 인증정보 없는 서버 주소여야 합니다.');
    config.baseUrl = url.origin;
    assert(positiveId(config.movieId), 'movieId를 실제 영화 ID로 지정하세요.');
    assert(Number.isInteger(config.partySize) && config.partySize >= 1 && config.partySize <= 6, 'partySize: 1~6');
    for (const [key, min, max] of [['rounds', 1, 100], ['iterations', 1, 10000],
        ['warmupIterations', 0, 100], ['timeoutMs', 100, 300000], ['pauseMs', 0, 60000]]) {
        assert(Number.isInteger(config[key]) && config[key] >= min && config[key] <= max, `${key}: ${min}~${max}`);
    }
    assert(Array.isArray(config.concurrency) && config.concurrency.length > 0
        && config.concurrency.every(n => Number.isInteger(n) && n >= 1 && n <= 100)
        && new Set(config.concurrency).size === config.concurrency.length, 'concurrency: 서로 다른 1~100 정수 배열');
    assert(Array.isArray(config.ranges) && config.ranges.length > 0, 'ranges가 필요합니다.');
    const time = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
    assert(config.ranges.every(r => /^[a-zA-Z0-9_-]+$/.test(r.name) && time.test(r.from) && time.test(r.to)
        && r.from !== r.to) && new Set(config.ranges.map(r => r.name)).size === config.ranges.length,
    '범위 이름은 고유한 영숫자/하이픈, 시간은 서로 다른 HH:mm이어야 합니다.');
    const seoul = new Date(now.getTime() + 9 * 3600000);
    const today = seoul.toISOString().slice(0, 10);
    if (config.viewingDate === 'tomorrow') config.viewingDate = new Date(seoul.getTime() + 86400000).toISOString().slice(0, 10);
    const date = new Date(`${config.viewingDate}T00:00:00+09:00`);
    assert(/^\d{4}-\d{2}-\d{2}$/.test(config.viewingDate) && Number.isFinite(date.getTime())
        && new Date(date.getTime() + 9 * 3600000).toISOString().slice(0, 10) === config.viewingDate
        && config.viewingDate >= today, 'viewingDate는 오늘 이후 YYYY-MM-DD 또는 tomorrow여야 합니다.');
    if (config.viewingDate === today) assert(config.ranges.every(r => r.from > seoul.toISOString().slice(11, 16)),
        '오늘은 모든 범위 시작을 현재 한국 시간 이후로 지정하세요.');
    assert(typeof config.label === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(config.label), 'label: 영숫자/하이픈 1~80자');
    return config;
}

export function bodyFor(config, range) {
    return { entryPoint: 'MOVIE_SMART', movieId: config.movieId, viewingDate: config.viewingDate,
        partySize: config.partySize, startTimeFrom: range.from, startTimeTo: range.to,
        audience: { adultCount: config.partySize, youthCount: 0, guardianAccompanying: false, companionsEligible: false } };
}

export async function request(config, path, token, body, key, method) {
    const start = performance.now();
    try {
        const response = await fetch(config.baseUrl + path, {
            method: method ?? (body !== undefined || key ? 'POST' : 'GET'), redirect: 'error',
            headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}),
                ...(key ? { 'Idempotency-Key': key } : {}) },
            body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(config.timeoutMs),
        });
        const text = await response.text();
        let data;
        try { data = JSON.parse(text); } catch { /* A non-JSON response is reported through schema checks. */ }
        return { status: response.status, ms: performance.now() - start, data };
    } catch (error) {
        return { status: 0, ms: performance.now() - start,
            error: error.name === 'TimeoutError' ? 'TIMEOUT' : 'NETWORK_ERROR' };
    }
}

async function saveJournal(directory, record) {
    const path = join(directory, `${record.key}.json`);
    await writeFile(path + '.tmp', json(record));
    await rename(path + '.tmp', path);
}

// Only replay this attempt's exact request/key; never enumerate and cancel other bookings.
export async function cleanup(config, user, record, save = async () => {}) {
    if (!record.groupIds?.length && record.uncertain) {
        const replay = await request(config, endpoint, user.token, record.body, record.key);
        record.groupIds = idsFrom(replay) ?? [];
        if (!record.groupIds.length) { await save(record); return false; }
        record.uncertain = false;
        await save(record);
    }
    for (const id of record.groupIds ?? []) {
        record.cancelKeys ??= {};
        record.cancelKeys[id] ??= randomUUID();
        await save(record);
        let response;
        for (let attempt = 0; attempt < 3; attempt++) {
            response = await request(config, `/api/booking-groups/${id}/cancel`, user.token, undefined, record.cancelKeys[id]);
            if (ok(response)) break;
            await delay(100 * (attempt + 1));
        }
        if (!ok(response)) { await save(record); return false; }
    }
    if (record.groupIds?.length) {
        const active = await request(config, activePath, user.token);
        if (!ok(active) || !Array.isArray(active.data) || active.data.some(g => record.groupIds.includes(g.id))) return false;
    }
    record.cleaned = true;
    await save(record);
    return true;
}

export async function sample(config, user, range, meta, journalDir) {
    const record = { key: randomUUID(), userId: user.id, body: bodyFor(config, range), uncertain: true, cleaned: false };
    const save = r => saveJournal(journalDir, r);
    await save(record); // Persist BEFORE sending: recover even after process termination or a lost response.
    const created = await request(config, endpoint, user.token, record.body, record.key);
    record.groupIds = idsFrom(created) ?? [];
    // 4xx has no successful creation. 5xx/timeout/invalid success may have committed remotely.
    record.uncertain = !record.groupIds.length && !(created.status >= 400 && created.status < 500);
    await save(record);
    const row = { ...meta, range: range.name, key: record.key, createStatus: created.status,
        createMs: created.ms, createOk: record.groupIds.length > 0, readOk: false,
        candidateCount: record.groupIds.length, error: created.error ?? (record.groupIds.length ? null : `CREATE_${created.status}`) };
    try {
        if (row.createOk) {
            // Match the UI: use embedded details, otherwise fetch active candidates.
            const embedded = created.data?.initial;
            const read = embedded && Array.isArray(embedded.candidates)
                ? { status: created.status, ms: 0, data: embedded }
                : await request(config, endpoint, user.token);
            row.initialInCreate = Boolean(embedded && Array.isArray(embedded.candidates));
            row.readStatus = read.status; row.readMs = read.ms; row.flowMs = created.ms + read.ms;
            const candidates = Array.isArray(read.data?.candidates) ? read.data.candidates : [];
            row.readOk = ok(read) && record.groupIds.every(id => candidates.some(c => c.groupId === id));
            if (!row.readOk) row.error = read.error ?? `READ_${read.status}_OR_MISSING_CANDIDATES`;
            row.outcomes = candidates.filter(c => record.groupIds.includes(c.groupId)).map(c => ({
                kind: c.kind, zone: c.zone, showtimeId: c.showtimeId, status: c.status,
                holding: c.payment?.reservation?.status === 'PENDING',
                waiting: c.waiting?.items?.some(q => q.status === 'WAITING') ?? false,
            }));
        }
    } finally {
        row.cleanupOk = await cleanup(config, user, record, save);
    }
    return row;
}

export function stats(values) {
    const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
    const percentile = p => sorted.length ? sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)] : null;
    return { n: sorted.length, p50: percentile(.5), p95: percentile(.95), p99: percentile(.99),
        mean: sorted.length ? sorted.reduce((a, b) => a + b, 0) / sorted.length : null, max: sorted.at(-1) ?? null };
}

export function summarize(rows) {
    const groups = new Map();
    for (const row of rows.filter(r => !r.warmup)) {
        const key = `${row.range}/c${row.concurrency}`;
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(row);
    }
    return [...groups].map(([scenario, samples]) => ({ scenario, attempts: samples.length,
        successes: samples.filter(r => r.createOk && r.readOk).length,
        failures: samples.filter(r => !r.createOk || !r.readOk).length,
        cleanupFailures: samples.filter(r => !r.cleanupOk).length,
        create: stats(samples.filter(r => r.createOk).map(r => r.createMs)),
        firstRead: stats(samples.filter(r => r.readOk).map(r => r.readMs)),
        flow: stats(samples.filter(r => r.createOk && r.readOk).map(r => r.flowMs)),
        failedCreate: stats(samples.filter(r => !r.createOk).map(r => r.createMs)),
        holdingCandidates: samples.flatMap(r => r.outcomes ?? []).filter(c => c.holding).length,
        waitingCandidates: samples.flatMap(r => r.outcomes ?? []).filter(c => c.waiting).length,
    }));
}

export async function loginUsers(config, credentials) {
    assert(Array.isArray(credentials) && credentials.length >= Math.max(...config.concurrency), '동시 사용자 수만큼 서로 다른 테스트 계정이 필요합니다.');
    const users = [];
    for (const credential of credentials.slice(0, Math.max(...config.concurrency))) {
        assert(typeof credential.loginId === 'string' && typeof credential.password === 'string', '계정에는 loginId/password가 필요합니다.');
        const login = await request(config, '/api/auth/login', null, credential);
        assert(ok(login) && login.data?.accessToken, `테스트 계정 ${users.length + 1} 로그인 실패 (${login.status})`);
        const token = login.data.accessToken;
        const me = await request(config, '/api/users/me', token);
        assert(ok(me) && positiveId(me.data?.id) && !users.some(u => u.id === me.data.id), '서로 다른 사용자 계정을 지정하세요.');
        assert(me.data.preferredTheaters?.length && me.data.preferredSeats?.length, '테스트 계정의 선호 극장·좌석 설정이 필요합니다.');
        users.push({ id: me.data.id, token, preferences: {
            theaters: me.data.preferredTheaters.map(p => ({ id: p.theaterId, priority: p.priority })).sort((a,b) => a.priority-b.priority),
            seats: me.data.preferredSeats.map(p => ({ position: p.position, priority: p.priority })).sort((a,b) => a.priority-b.priority),
        } });
    }
    assert(users.every(u => JSON.stringify(u.preferences) === JSON.stringify(users[0].preferences)),
        '시간 범위 효과를 비교하려면 모든 테스트 계정의 극장·좌석 선호와 순서를 동일하게 설정하세요.');
    if (config.expectedPreferences) assert(users.every(u => JSON.stringify(u.preferences) === JSON.stringify(config.expectedPreferences)),
        '실행 준비 이후 계정 선호 설정이 바뀌었습니다. 다시 실행하세요.');
    return users;
}

async function report(directory, config, rows, note) {
    const summary = summarize(rows);
    await writeFile(join(directory, 'summary.json'), json({ config, note, summary }));
    const ms = n => n == null ? '-' : n.toFixed(1);
    const lines = ['# 스마트예매 API 벤치마크', '', `실험: ${config.label}`, `상태: ${note}`, '',
        '단위: ms. 성공 표본만 지연 통계에 포함하며 실패 건수는 별도 표시합니다.', '',
        '| 범위/동시 사용자 | 성공/전체 | 실패 | 생성 p95 | 첫 조회 p95 | 합계 p50 | 합계 p95 | 합계 p99 | 정리 실패 |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|'];
    for (const s of summary) lines.push(`| ${s.scenario} | ${s.successes}/${s.attempts} | ${s.failures} | ${ms(s.create.p95)} | ${ms(s.firstRead.p95)} | ${ms(s.flow.p50)} | ${ms(s.flow.p95)} | ${ms(s.flow.p99)} | ${s.cleanupFailures} |`);
    lines.push('', '합계는 후보 정보를 모두 받을 때까지 필요한 API 시간입니다. 생성 응답에 후보 상세가 있으면 추가 조회 없이 생성 시간만 사용합니다. 브라우저 렌더링·로그인·로컬 파일 기록·정리 시간은 제외합니다.',
        '표본 100개 미만의 p99는 참고값입니다. SQL/잠금 대기/서버 CPU 시간은 이 스크립트로 측정하지 않습니다.',
        '고정 동시 사용자 반복 방식이며 정리·휴식이 포함됩니다. 최대 처리량(RPS)이나 동시 클릭의 완전 동기화를 의미하지 않습니다.',
        '워밍업도 데이터를 생성·취소합니다. 취소 내역과 멱등성 기록은 DB에 남으므로 전후 비교는 같은 DB 스냅샷에서 시작하세요.');
    await writeFile(join(directory, 'report.md'), lines.join('\n') + '\n');
    return summary;
}

export async function runBench(config, credentials, directory, { recover = false, log = console.log } = {}) {
    await mkdir(join(directory, 'journal'), { recursive: true });
    const users = await loginUsers(config, credentials);
    if (recover) {
        for (const file of await readdir(join(directory, 'journal'))) {
            if (!file.endsWith('.json')) continue;
            const record = JSON.parse(await readFile(join(directory, 'journal', file), 'utf8'));
            if (record.cleaned) continue;
            const user = users.find(u => u.id === record.userId);
            assert(user && await cleanup(config, user, record, r => saveJournal(join(directory, 'journal'), r)), `복구 미완료: ${file}`);
        }
        log('미완료 요청 정리 완료'); return;
    }
    for (const user of users) {
        const active = await request(config, activePath, user.token);
        assert(ok(active) && Array.isArray(active.data) && active.data.length === 0,
            '테스트 계정에 기존 대기·선점이 있거나 상태 조회가 실패했습니다. 기존 신청은 자동 취소하지 않습니다.');
    }
    await writeFile(join(directory, 'manifest.json'), json({ ...config, users: users.map(({ id, preferences }) => ({ id, preferences })) }));
    const rows = [];
    let stopped = false;
    const stop = () => { stopped = true; log('현재 요청 정리 후 중단합니다.'); };
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
    let note = '완료';
    try {
        const cases = config.concurrency.flatMap(concurrency => config.ranges.map(range => ({ concurrency, range })));
        // Warm every case first; rotate measured order across rounds to reduce order bias.
        for (let round = -1; round < config.rounds && !stopped; round++) {
            const warmup = round === -1;
            const count = warmup ? config.warmupIterations : config.iterations;
            if (!count) continue;
            const offset = warmup ? 0 : round % cases.length;
            for (const c of [...cases.slice(offset), ...cases.slice(0, offset)]) {
                if (stopped) break;
                log(`${warmup ? '워밍업' : `라운드 ${round + 1}`} ${c.range.name}, 동시 ${c.concurrency}`);
                for (let iteration = 0; iteration < count && !stopped; iteration++) {
                    const settled = await Promise.allSettled(users.slice(0, c.concurrency).map(user => sample(config, user, c.range,
                        { round: round + 1, iteration, concurrency: c.concurrency, warmup }, join(directory, 'journal'))));
                    for (const result of settled) if (result.status === 'fulfilled') {
                        rows.push(result.value);
                        await appendFile(join(directory, 'samples.jsonl'), JSON.stringify(result.value) + '\n');
                    }
                    assert(settled.every(r => r.status === 'fulfilled' && r.value.cleanupOk), '정리 미완료. journal을 보존하고 --recover로 복구하세요.');
                    assert(!warmup || settled.every(r => r.value.createOk && r.value.readOk), '워밍업 실패. 영화·회차·선호 설정 또는 서버 상태를 확인하세요.');
                    await delay(config.pauseMs);
                }
            }
        }
        if (stopped) note = '사용자 중단';
    } catch (error) { note = error.message; throw error; }
    finally {
        process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
        await report(directory, config, rows, note);
    }
    assert(!stopped && rows.filter(r => !r.warmup).every(r => r.createOk && r.readOk), '중단 또는 실패 표본이 있습니다. report.md를 확인하세요.');
    return summarize(rows);
}

async function main() {
    const args = process.argv.slice(2);
    const configArg = args.find(a => !a.startsWith('--'));
    assert(configArg && args.filter(a => a.startsWith('--')).every(a => a === '--run' || a.startsWith('--recover=')),
        '사용법: node scripts/smart-booking-benchmark/run.mjs <설정.json> [--run | --recover=결과폴더]');
    const recoverPath = args.find(a => a.startsWith('--recover='))?.slice('--recover='.length);
    assert(!(recoverPath && args.includes('--run')), '--run과 --recover는 함께 사용할 수 없습니다.');
    const raw = JSON.parse(await readFile(resolve(configArg), 'utf8'));
    // Recovery uses the original URL/date/config, even after the experiment date has passed.
    const config = recoverPath ? JSON.parse(await readFile(join(resolve(recoverPath), 'manifest.json'), 'utf8')) : normalize(raw);
    const attempts = config.ranges.length * config.concurrency.reduce((a, b) => a + b, 0)
        * (config.rounds * config.iterations + config.warmupIterations);
    console.log(json({ server: config.baseUrl, label: config.label, date: config.viewingDate,
        movieId: config.movieId, ranges: config.ranges, concurrency: config.concurrency, attemptsIncludingWarmup: attempts }));
    if (!args.includes('--run') && !recoverPath) { console.log('계획만 출력했습니다. 실제 생성·취소는 --run으로 실행합니다.'); return; }
    const credentials = JSON.parse(await readFile(resolve(dirname(resolve(configArg)), raw.usersFile), 'utf8'));
    const directory = recoverPath ? resolve(recoverPath) : resolve('benchmark-results',
        `smart-${new Date().toISOString().replaceAll(':', '-')}-${randomUUID().slice(0, 8)}`);
    console.log(`결과: ${directory}`);
    await runBench(config, credentials, directory, { recover: Boolean(recoverPath) });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
