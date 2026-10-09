import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
const fixture = JSON.parse(open('/results/fixture.json'));
const failures = new Rate('business_failures');
const latency = new Trend('business_latency', true);
const gateErrors = new Rate('admission_errors');
const gateWait = new Trend('admission_wait', true);
const journeys = new Counter('completed_journeys');
const bookings = new Counter('completed_bookings');
const successfulRequests = new Counter('successful_business_requests');
export const options = {
  vus: Number(__ENV.USERS), duration: __ENV.DURATION,
  insecureSkipTLSVerify: true, noCookiesReset: true,
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
  thresholds: { business_failures: ['rate<0.01'], admission_errors: ['rate<0.01'],
    completed_journeys: ['count>0'] },
};
const base = 'https://nginx';
let sequence = 0;
let token;
let firstFailure = false;
function request(method, path, body, user) {
  const r = http.request(method, base + path, body == null ? null : JSON.stringify(body), {
    headers: { Authorization: 'Bearer ' + (token || fixture.tokens[user]), 'Content-Type': 'application/json',
      'Idempotency-Key': `bench-${user}-${__ITER}-${++sequence}-${Date.now()}` },
    timeout: '8s', tags: { name: path.replace(/\d+/g, ':id') },
  });
  const bad = r.status < 200 || r.status >= 300;
  if (!bad) successfulRequests.add(1);
  failures.add(bad); latency.add(r.timings.duration);
  if (bad && !firstFailure) { console.log(`Failed ${method} ${path}: HTTP ${r.status}`); firstFailure = true; }
  return r;
}
function enter() {
  const started = Date.now();
  let first = true;
  while (Date.now() - started < 120000) {
    const r = first ? http.post(base + '/api/admission/enter', null, {timeout:'8s'})
      : http.get(base + '/api/admission/status', {timeout:'8s'});
    gateErrors.add(r.status !== 200);
    if (r.status !== 200) return false;
    const state = r.json();
    if (state.state === 'ADMITTED') { gateWait.add(Date.now() - started); return true; }
    // A disabled or missing admission implementation is not equivalent to the deployment path.
    if (!['WAITING', 'FULL', 'EXPIRED'].includes(state.state)) { gateErrors.add(true); return false; }
    first = state.state !== 'WAITING';
    sleep(Math.max(3, Math.min(60, state.pollAfterSeconds || 5)));
  }
  gateErrors.add(true);
  return false;
}
export default function () {
  const user = Number(__ENV.USER_OFFSET) + __VU;
  if (__ITER === 0) sleep((__VU - 1) * 0.25);
  if (!enter()) { sleep(3); return; }
  let success = true;
  try {
    if (__ITER === 0) {
      const page = http.get(base + '/', {timeout:'8s'});
      const ok = page.status === 200 && page.body.includes('<html');
      failures.add(!ok); success = success && ok;
    }
    const suite = __ENV.SUITE === 'all' ? ['browse', 'smart', 'manual', 'login'][__ITER % 4] : __ENV.SUITE;
    const show = (user - 1) % 20 + 1;
    if (suite === 'login') {
      const login = request('POST', '/api/auth/login', {loginId:`load-${user}`, password:fixture.password}, user);
      success = login.status === 200 && Boolean(login.json().accessToken);
      if (success) token = login.json().accessToken;
      else failures.add(true);
    } else if (suite === 'browse') {
      for (const path of ['/api/main', '/api/movies', '/api/theaters', `/api/showtimes?movieId=1&date=${fixture.date}`, `/api/showtimes/${show}/seats`]) {
        success = request('GET', path, null, user).status === 200 && success;
        sleep(1);
      }
    } else {
      const group = request('POST', '/api/booking-groups', {entryPoint:suite === 'smart' ? 'THEATER_SMART' : 'THEATER_NORMAL',
        movieId:1, viewingDate:fixture.date, partySize:1, selectedShowtimeId:show,
        audience:{adultCount:1, youthCount:0}}, user);
      success = group.status === 201;
      if (success) {
        const groupId = group.json().id;
        let seatIds;
        if (suite === 'manual') {
          const seatMap = request('GET', `/api/showtimes/${show}/seats`, null, user);
          success = seatMap.status === 200;
          // Spread manual selections across rows instead of targeting one hot seat.
          seatIds = [(show - 1) * 100 + Math.floor((user - 1) / 20) % 100 + 1];
        }
        const held = success ? request('POST', `/api/booking-groups/${groupId}/${suite === 'smart' ? 'smart' : 'manual'}-hold`,
          suite === 'smart' ? null : {seatIds}, user) : null;
        success = held && [200,201].includes(held.status);
        if (success) {
          const id = held.json().id;
          const paid = request('POST', `/api/reservations/${id}/mock-payments`, {paymentMethod:'MOCK'}, user);
          success = [200,201].includes(paid.status);
          const cancelled = request('POST', `/api/reservations/${id}/cancel`, null, user);
          success = cancelled.status === 200 && success;
          if (success) bookings.add(1);
        }
      }
    }
    failures.add(!success);
    if (success) journeys.add(1, {journey:suite});
  } catch (error) {
    failures.add(true);
    if (!firstFailure) { console.log(`Journey failed: ${error.message}`); firstFailure = true; }
  } finally {
    const leave = http.post(base + '/api/admission/leave', null, {timeout:'8s'});
    gateErrors.add(leave.status !== 200);
  }
  sleep(3);
}
export function handleSummary(data) { return {'/results/summary.json':JSON.stringify(data, null, 2)}; }
