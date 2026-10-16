import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { check } from 'k6';

const fixture = JSON.parse(open(__ENV.FIXTURE || '/results/fixture.json'));
const base = __ENV.BASE_URL;
if (!base) throw new Error('BASE_URL is required');
const suite = __ENV.SUITE || 'query';
if (!['query', 'booking'].includes(suite)) throw new Error('SUITE must be query or booking');
const maxVUs = Number(__ENV.MAX_VUS || 100);
if (maxVUs > 200) throw new Error('Fixture supports at most 200 unique VUs');
const good = new Counter('successful_requests');
const paid = new Counter('completed_bookings');
const journeys = new Counter('completed_journeys');
const failed = new Rate('journey_failed');
const routed = new Counter('upstream_requests');
const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '3m';
const p95 = Number(__ENV.P95_MS || 1000);
export const options = {
  scenarios: { load: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
    preAllocatedVUs: Math.min(20, maxVUs), maxVUs } },
  thresholds: {
    checks: ['rate==1'], journey_failed: ['rate<0.01'], http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count==0'],
    ...Object.fromEntries((suite === 'query' ? ['seats', 'showtimes'] : ['group', 'hold', 'pay', 'cancel'])
      .map(name => [`http_req_duration{name:${name}}`, [`p(95)<${p95}`]])),
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

let sequence = 0;
function call(method, path, body, name, user) {
  const r = http.request(method, base + path, body == null ? null : JSON.stringify(body), {
    timeout: '10s', tags: { name }, headers: {
      Authorization: `Bearer ${fixture.tokens[user]}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `${__ENV.RUN_ID || 'lab'}-${__VU}-${__ITER}-${++sequence}-${Date.now()}`,
    },
  });
  routed.add(1, { upstream: r.headers['X-Lab-Upstream'] || 'missing' });
  if (r.status >= 200 && r.status < 300) good.add(1, { endpoint: name });
  return r;
}

export default function () {
  const user = __VU;
  const show = (user - 1) % 20 + 1;
  let success = false;
  let reservation;
  try {
    if (suite === 'query') {
      const seats = __ITER % 2 === 0;
      const r = call('GET', seats ? `/api/showtimes/${show}/seats`
        : `/api/showtimes?movieId=1&date=${fixture.date}`, null, seats ? 'seats' : 'showtimes', user);
      success = check(r, { 'query returns JSON': v => v.status === 200 && v.json() != null });
    } else {
      const g = call('POST', '/api/booking-groups', { entryPoint: 'THEATER_NORMAL', movieId: 1,
        viewingDate: fixture.date, partySize: 1, selectedShowtimeId: show,
        audience: { adultCount: 1, youthCount: 0 } }, 'group', user);
      if (!check(g, { 'group created': r => r.status === 201 && r.json().id > 0 })) return;
      // Each VU owns a distinct seat; same-seat contention is tested separately by lab.py race.
      const seat = (show - 1) * 100 + Math.floor((user - 1) / 20) + 1;
      const held = call('POST', `/api/booking-groups/${g.json().id}/manual-hold`, { seatIds: [seat] }, 'hold', user);
      if (!check(held, { 'seat held': r => [200, 201].includes(r.status) && r.json().seatIds.includes(seat) })) return;
      reservation = held.json().id;
      const payment = call('POST', `/api/reservations/${reservation}/mock-payments`, { paymentMethod: 'MOCK' }, 'pay', user);
      if (!check(payment, { 'mock payment confirmed': r => [200, 201].includes(r.status)
        && r.json().status === 'SUCCESS' && r.json().reservation.status === 'CONFIRMED' })) return;
      paid.add(1);
      const cancelled = call('POST', `/api/reservations/${reservation}/cancel`, null, 'cancel', user);
      success = check(cancelled, { 'seat recycled': r => r.status === 200 && r.json().status === 'CANCELLED' });
      if (success) reservation = null;
    }
    if (success) journeys.add(1);
  } finally {
    failed.add(!success);
    if (reservation) call('POST', `/api/reservations/${reservation}/cancel`, null, 'cleanup', user);
  }
}

export function handleSummary(data) {
  return { [`/results/${__ENV.RUN_ID || 'run'}.json`]: JSON.stringify({
    metadata: { suite, rate, duration, maxVUs, base, nodes: __ENV.NODES,
      phase: __ENV.PHASE || 'measurement', fixtureGeneratedAt: fixture.generatedAt,
      note: 'Rate is journeys/s; booking journey contains multiple HTTP requests. Wi-Fi is included in latency.' },
    ...data,
  }, null, 2) };
}
