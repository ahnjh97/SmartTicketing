import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();
const duration = new Trend('nearby_distance_duration', true);
const latitude = Number(__ENV.K6_LATITUDE || 37.5665);
const longitude = Number(__ENV.K6_LONGITUDE || 126.9780);

export function setup() {
  if (!__ENV.K6_ACCESS_TOKEN) {
    fail('run-cache-comparison.ps1 must provide K6_ACCESS_TOKEN for /api/theaters/nearby.');
  }
  const response = http.get(`${BASE_URL}/api/theaters/nearby?latitude=${latitude}&longitude=${longitude}&sort=DISTANCE`, {
    headers: { Authorization: `Bearer ${__ENV.K6_ACCESS_TOKEN}` },
    responseType: 'text',
  });
  check(response, { 'nearby setup succeeded': (r) => r.status === 200 });
  if (response.status !== 200) {
    const body = response.body ? String(response.body).slice(0, 500) : '<empty body>';
    fail(`Nearby setup failed: HTTP ${response.status}, body=${body}`);
  }
  return { token: __ENV.K6_ACCESS_TOKEN };
}

export default function (data) {
  get(
    `/api/theaters/nearby?latitude=${latitude}&longitude=${longitude}&sort=DISTANCE`,
    duration,
    { headers: { Authorization: `Bearer ${data.token}` } }
  );
}

export function handleSummary(data) {
  return writeSummary(data, '05-distance-query', ['nearby_distance_duration']);
}