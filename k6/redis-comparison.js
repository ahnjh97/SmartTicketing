import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate, Counter } from 'k6/metrics';

// Parse only inside SharedArray callbacks: retaining the full fixture outside them
// duplicates every account/token in each VU and competes with the local server.
const users = new SharedArray('users', () => JSON.parse(open(__ENV.FIXTURE)).users);
const dispatch = new SharedArray('dispatch', () => JSON.parse(open(__ENV.FIXTURE)).dispatch);
const base = __ENV.BASE_URL || 'http://127.0.0.1:18081';
const kind = __ENV.CASE;
const latency = new Trend('operation_ms', true);
const assigned = new Trend('assignment_ms', true);
const completed = new Rate('operation_success');
const wrong = new Counter('wrong_results');
const polls = new Counter('assignment_polls');
export const options = {
    scenarios: kind === 'dispatch' ? { load: { executor: 'per-vu-iterations', vus: dispatch.length, iterations: 1, maxDuration: '90s' } }
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
        const start = Date.now();
        const cancel = http.post(`${base}/api/reservations/${u.reservation}/cancel`, '{}', params(u.ownerToken, 'cancel', `benchmark-cancel-${u.reservation}`));
        let ok = cancel.status === 200;
        if (!ok) console.error(`cancel failed status=${cancel.status} body=${String(cancel.body).slice(0,300)}`);
        if (ok) {
            const deadline = Date.now() + 60000;
            ok = false;
            while (Date.now() < deadline) {
                const response = http.get(`${base}/api/booking-groups/${u.group}`, params(u.token, 'assignment-status'));
                polls.add(1);
                if (response.status === 200 && response.json('activeReservationId')) {
                    const id = response.json('activeReservationId');
                    const held = http.get(`${base}/api/reservations/${id}`, params(u.token, 'assigned-reservation'));
                    ok = held.status === 200 && held.json('seatIds').length === 1 && held.json('seatIds')[0] === u.seat;
                    if (!ok) wrong.add(1);
                    assigned.add(Date.now() - start);
                    break;
                }
                sleep(0.1);
            }
        }
        completed.add(ok); latency.add(Date.now() - start); check(ok, { 'cancel then exact waiter acquired': v => v });
        return;
    }
    const u = users[(__VU - 1 + __ITER * 17) % users.length];
    const waiting = kind !== 'status';
    const response = http.get(`${base}/api/booking-groups/${u.group}${waiting ? '/waiting-queues' : ''}`, params(u.token, waiting ? 'waiting-rank' : 'group-status'));
    latency.add(response.timings.duration);
    let ok = response.status === 200;
    if (ok) {
        const data = response.json();
        ok = waiting ? data.items.length === 1 && data.items[0].aheadCount === u.ahead : data.id === u.group && data.status === 'ACTIVE';
        if (!ok) wrong.add(1);
    }
    completed.add(ok); check(ok, { 'response and domain state valid': v => v });
}
export function handleSummary(data) { return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2) }; }
