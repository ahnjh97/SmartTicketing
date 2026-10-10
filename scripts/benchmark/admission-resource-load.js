import http from 'k6/http';
import { sleep } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';

const fixture = JSON.parse(open('/results/fixture.json'));
const failures = new Rate('business_failures');
const latency = new Trend('business_latency', true);
const bookings = new Counter('completed_smart_cycles');
const admitted = new Counter('admitted_users');
const gateFailures = new Rate('admission_failures');
export const options = {
  vus: Number(__ENV.VUS), duration: __ENV.DURATION || '120s',
  insecureSkipTLSVerify: true, noCookiesReset: true,
  thresholds: { business_failures: ['rate<0.01'], business_latency: ['p(95)<1000'],
    admission_failures: ['rate<0.01'], admitted_users: [`count>=${Number(__ENV.VUS)}`] },
};
const base = 'https://nginx';
let entered = false;
let attempted = false;
let loggedFailure = false;
let sequence = 0;
function request(method, path, body, user) {
  const result = http.request(method, base + path, body === null ? null : JSON.stringify(body), {
    headers: { Authorization: 'Bearer ' + fixture.tokens[user], 'Content-Type': 'application/json',
      'Idempotency-Key': `load-${__ENV.SHARD}-${__VU}-${__ITER}-${++sequence}-${Date.now()}` },
    timeout: '8s', tags: { name: path.replace(/\d+/g, ':id') },
  });
  const bad = result.status < 200 || result.status >= 300;
  failures.add(bad); latency.add(result.timings.duration);
  if (bad && !loggedFailure) { console.log(`${path} status=${result.status} body=${result.body?.slice(0, 300)}`); loggedFailure = true; }
  return result;
}
export default function () {
  const user = Number(__ENV.SHARD) * Number(__ENV.VUS) + __VU;
  if (!entered) {
    if (__ITER === 0) sleep((__VU - 1) * 0.25);
    const r = attempted ? http.get(base + '/api/admission/status', { timeout: '8s' })
      : http.post(base + '/api/admission/enter', null, { timeout: '8s' });
    attempted = true;
    gateFailures.add(r.status !== 200);
    let state; try { state = r.json().state; } catch (_) { /* proxy failure remains observable */ }
    if (state !== 'ADMITTED') { sleep(5); return; }
    admitted.add(1); entered = true;
  }
  const show = (user - 1) % 20 + 1;
  const paths = ['/api/main', '/api/movies', `/api/showtimes?movieId=1&date=${fixture.date}`,
    `/api/showtimes/${show}/seats`, '/api/theaters'];
  request('GET', paths[__ITER % paths.length], null, user);
  if (user % 5 === 0 && __ITER % 5 === 0) {
    const group = request('POST', '/api/booking-groups', {
      entryPoint: 'THEATER_SMART', movieId: 1, viewingDate: fixture.date, partySize: 1,
      selectedShowtimeId: show, audience: { adultCount: 1, youthCount: 0 },
    }, user);
    if (group.status === 201) {
      const id = group.json().id;
      const held = request('POST', `/api/booking-groups/${id}/smart-hold`, null, user);
      if (held.status === 200 || held.status === 201) {
        const reservation = held.json().id;
        const cancelled = request('POST', `/api/reservations/${reservation}/cancel`, null, user);
        if (cancelled.status === 200) bookings.add(1);
      }
    }
  }
  sleep(3);
}
export function handleSummary(data) {
  return { [`/results/load-${__ENV.SHARD}.json`]: JSON.stringify(data, null, 2) };
}
