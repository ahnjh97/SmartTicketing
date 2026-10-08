import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();

const duration = new Trend('seat_map_duration', true);
const configuredShowtimeId = Number(__ENV.K6_SHOWTIME_ID || 0);

export function setup() {
  if (!configuredShowtimeId) {
    fail('Seat-map benchmark requires K6_SHOWTIME_ID. Pass -ShowtimeId <id> to run-cache-comparison.ps1.');
  }

  // Validate the fixed showtime once before the load test.
  // OFF/ON comparisons use exactly the same showtime.
  const url = `${BASE_URL}/api/showtimes/${configuredShowtimeId}/seats`;
  const response = http.get(url, { responseType: 'text' });

  check(response, { 'seat-map setup succeeded': (r) => r.status === 200 });
  if (response.status !== 200) {
    const body = response.body ? String(response.body).slice(0, 500) : '<empty body>';
    fail(`Seat-map setup failed: GET /api/showtimes/${configuredShowtimeId}/seats -> HTTP ${response.status}, body=${body}`);
  }

  return { showtimeId: configuredShowtimeId };
}

export default function (data) {
  get(`/api/showtimes/${data.showtimeId}/seats`, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-seat-map-query', ['seat_map_duration']);
}