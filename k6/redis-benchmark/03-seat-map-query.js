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

  const url = `${BASE_URL}/api/showtimes?movieId=${movieId}&date=${date}`;
  const response = http.get(url, { responseType: 'text' });

  check(response, { 'showtime discovery succeeded': (r) => r.status === 200 });
  if (response.status !== 200) {
    const body = response.body ? String(response.body).slice(0, 500) : '<empty body>';
    fail(`Showtime discovery failed: HTTP ${response.status}, body=${body}`);
  }

  if (!response.body) {
    fail(`Showtime discovery returned an empty body for movieId=${movieId}, date=${date}`);
  }

  let body;
  try {
    body = JSON.parse(response.body);
  } catch (error) {
    fail(`Showtime discovery returned invalid JSON for movieId=${movieId}, date=${date}: ${String(error)}`);
  }

  if (!body.items || body.items.length === 0) {
    fail(`No scheduled showtime found for movieId=${movieId}, date=${date}`);
  }

  const showtimeId = Number(body.items[0].id);
  if (!showtimeId) {
    fail(`Showtime discovery returned an invalid showtime id: ${body.items[0].id}`);
  }

  return { showtimeId };
}

export default function (data) {
  get(`/api/showtimes/${data.showtimeId}/seats`, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-seat-map-query', ['seat_map_duration']);
}
