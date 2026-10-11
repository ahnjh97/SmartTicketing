import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate, Counter } from 'k6/metrics';
import { recordJdbc } from './request-jdbc.js';
import execution from 'k6/execution';

// Parse only inside SharedArray callbacks: retaining the full fixture outside them
// duplicates every account/token in each VU and competes with the local server.
const users = new SharedArray('users', () => JSON.parse(open(__ENV.FIXTURE)).users);
const dispatch = new SharedArray('dispatch', () => JSON.parse(open(__ENV.FIXTURE)).dispatch);
const base = __ENV.BASE_URL || 'http://127.0.0.1:18081';
const kind = __ENV.CASE;
const latency = new Trend('operation_ms', true);
const assigned = new Trend('assignment_ms', true);
const observed = new Trend('holding_observed_ms', true);
const cancelLatency = new Trend('cancel_ms', true);
const completed = new Rate('operation_success');
const wrong = new Counter('wrong_results');
const polls = new Counter('assignment_polls');
export const options = {
    scenarios: kind === 'dispatch' ? { load: { executor: 'per-vu-iterations', vus: dispatch.length, iterations: 1, maxDuration: '90s' } }
        : __ENV.BENCH_REQUEST_METRICS === 'true' ? { load: { executor: 'shared-iterations', vus: 1,
            iterations: Number(__ENV.READ_ITERATIONS), maxDuration: '5m' } }
        : __ENV.RATE ? { load: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE), timeUnit: '1s',
            preAllocatedVUs: Number(__ENV.PRE_VUS), maxVUs: Number(__ENV.MAX_VUS), duration: __ENV.DURATION, gracefulStop: '15s' } }
        : { load: { executor: 'constant-vus', vus: Number(__ENV.VUS), duration: __ENV.DURATION || '25s', gracefulStop: '15s' } },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: { operation_success: ['rate>0.99'], wrong_results: ['count==0'] },
};
function params(token, name, key) {
    return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
        tags: { name }, timeout: '15s' };
}
export default function () {
    wrong.add(0);
    if (kind === 'dispatch') {
        const u = dispatch[__VU - 1];
        const before = http.get(`${base}/api/booking-groups/${u.group}/waiting-queues`, params(u.token, 'first-waiter-precondition'));
        const ready = before.status === 200 && before.json('items').length === 1
            && before.json('items')[0].aheadCount === 0 && before.json('items')[0].status === 'WAITING'
            && !before.json('activeReservationId');
        if (!ready) { wrong.add(1); completed.add(false); check(false, { 'first waiter ready': v => v }); return; }
        const start = Date.now();
        const cancel = http.post(`${base}/api/reservations/${u.reservation}/cancel`, '{}', params(u.ownerToken, 'cancel', `benchmark-cancel-${u.reservation}`));
        cancelLatency.add(cancel.timings.duration);
        let ok = cancel.status === 200;
        if (!ok) console.error(`cancel failed status=${cancel.status} body=${String(cancel.body).slice(0,300)}`);
        if (ok) {
            const deadline = Date.now() + 60000;
            ok = false;
            while (Date.now() < deadline) {
                const response = http.get(`${base}/api/booking-groups/${u.group}`, params(u.token, 'assignment-status'));
                polls.add(1);
                if (response.status === 200 && response.json('status') === 'HOLDING' && response.json('activeReservationId')) {
                    const observedMs = Date.now() - start;
                    const id = response.json('activeReservationId');
                    const held = http.get(`${base}/api/reservations/${id}`, params(u.token, 'assigned-reservation'));
                    ok = held.status === 200 && held.json('status') === 'PENDING' && held.json('seatIds').length === 1 && held.json('seatIds')[0] === u.seat;
                    if (!ok) wrong.add(1);
                    if (ok) { observed.add(observedMs); assigned.add(Date.now() - start); }
                    break;
                }
                sleep(0.1);
            }
        }
        completed.add(ok); latency.add(Date.now() - start); check(ok, { 'cancel then exact waiter acquired': v => v });
        return;
    }
    const index = __ENV.BENCH_REQUEST_METRICS === 'true' ? execution.scenario.iterationInTest * 137 : __VU - 1 + __ITER * 17;
    const u = users[index % users.length];
    const waiting = kind !== 'status';
    const response = http.get(`${base}/api/booking-groups/${u.group}${waiting ? '/waiting-queues' : ''}`, params(u.token, waiting ? 'waiting-rank' : 'group-status'));
    latency.add(response.timings.duration);
    let ok = response.status === 200;
    if (ok) {
        const data = response.json();
        ok = waiting ? data.items.length === 1 && data.items[0].aheadCount === u.ahead : data.id === u.group && data.status === 'ACTIVE';
        if (!ok) wrong.add(1);
    }
    if (ok && __ENV.BENCH_REQUEST_METRICS === 'true') ok = recordJdbc(response);
    completed.add(ok); check(ok, { 'response and domain state valid': v => v });
}
export function handleSummary(data) {
    data.load = kind === 'dispatch' ? {dispatchCases:dispatch.length,pollMs:100}
        : __ENV.BENCH_REQUEST_METRICS === 'true' ? {executor:'shared-iterations',vus:1,iterations:Number(__ENV.READ_ITERATIONS),userStep:137}
        : {rate:Number(__ENV.RATE||0),duration:__ENV.DURATION,preVus:Number(__ENV.PRE_VUS||__ENV.VUS),maxVus:Number(__ENV.MAX_VUS||__ENV.VUS)};
    return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) };
}
