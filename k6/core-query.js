import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { recordJdbc } from './request-jdbc.js';
const success = new Rate('operation_success');
const latency = new Trend('operation_ms', true);
export const options = {
  scenarios: { load: { executor: 'constant-arrival-rate', rate: Number(__ENV.REDIS_BENCH_RATE), timeUnit: '1s',
    duration: __ENV.REDIS_BENCH_DURATION, preAllocatedVUs: Number(__ENV.REDIS_BENCH_PRE_VUS), maxVUs: Number(__ENV.REDIS_BENCH_MAX_VUS) } },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
  thresholds: { operation_success: ['rate==1'], http_req_failed: ['rate==0'], dropped_iterations: ['count==0'] },
};
export default function () {
  const seat = __ENV.CASE === 'seats';
  const path = seat ? `/api/showtimes/${__ENV.K6_SHOWTIME_ID}/seats`
    : `/api/showtimes?movieId=${__ENV.K6_MOVIE_ID}&date=${__ENV.K6_DATE}`;
  const r = http.get(__ENV.BASE_URL + path, { tags: { name: __ENV.CASE }, timeout: '15s' });
  let ok = false;
  try {
    const d = r.json();
    ok = r.status === 200 && (seat ? d.showtimeId === Number(__ENV.K6_SHOWTIME_ID) && d.seats.length === 120
      && d.totalSeats === 120 && d.availableSeats === 120 && d.seats.every(s => s.status === 'AVAILABLE'
        && /^[A-J]$/.test(s.row) && s.number >= 1 && s.number <= 12
        && s.segment === (s.number <= 3 ? 'left' : s.number <= 9 ? 'center' : 'right'))
      && new Set(d.seats.map(s => `${s.row}-${s.number}`)).size === 120
      : d.items.length === 20 && d.items.every(s => s.movieId === Number(__ENV.K6_MOVIE_ID) && s.totalSeats === 120 && s.availableSeats === 120));
  } catch (_) { /* malformed responses count as failures */ }
  if (ok) ok = recordJdbc(r);
  latency.add(r.timings.duration); success.add(ok); check(ok, { 'fixture response matches': v => v });
}
export function handleSummary(data) {
  data.load = {rate:Number(__ENV.REDIS_BENCH_RATE),duration:__ENV.REDIS_BENCH_DURATION,
    preVus:Number(__ENV.REDIS_BENCH_PRE_VUS),maxVus:Number(__ENV.REDIS_BENCH_MAX_VUS)};
  return { [__ENV.RESULT_FILE]: JSON.stringify(data, null, 2) };
}
