import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();

const duration = new Trend('seat_map_duration', true);
const configuredShowtimeId = Number(__ENV.K6_SHOWTIME_ID || 0);
const movieId = Number(__ENV.K6_MOVIE_ID || 1);
const date = __ENV.K6_DATE || new Date(Date.now() + 86400000).toISOString().slice(0, 10);

export function setup() {
  if (configuredShowtimeId) return { showtimeId: configuredShowtimeId };

  const response = http.get(`${BASE_URL}/api/showtimes?movieId=${movieId}&date=${date}`);
  check(response, { 'showtime discovery succeeded': (r) => r.status === 200 });
  if (response.status !== 200) fail(`Showtime discovery failed: HTTP ${response.status}`);

  const body = response.json();
  if (!body.items || body.items.length === 0) {
    fail(`No scheduled showtime found for movieId=${movieId}, date=${date}`);
  }

  return { showtimeId: body.items[0].id };
}

export default function (data) {
  get(`/api/showtimes/${data.showtimeId}/seats`, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-seat-map-query', ['seat_map_duration']);
}
