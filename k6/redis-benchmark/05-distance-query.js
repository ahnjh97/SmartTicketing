import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();
const duration = new Trend('nearby_distance_duration', true);
const latitude = Number(__ENV.K6_LATITUDE || 37.5665);
const longitude = Number(__ENV.K6_LONGITUDE || 126.9780);

export function setup() {
  if (__ENV.K6_ACCESS_TOKEN) return { token: __ENV.K6_ACCESS_TOKEN };
  if (!__ENV.K6_LOGIN_ID || !__ENV.K6_PASSWORD) {
    fail('Set K6_ACCESS_TOKEN or K6_LOGIN_ID + K6_PASSWORD for /api/theaters/nearby.');
  }

  const response = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ loginId: __ENV.K6_LOGIN_ID, password: __ENV.K6_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(response, { 'login succeeded': (r) => r.status === 200 });
  if (response.status !== 200) fail(`Login failed: HTTP ${response.status}`);
  return { token: response.json().accessToken };
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
