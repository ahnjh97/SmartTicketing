import { fail } from 'k6';
import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();
const duration = new Trend('seat_map_duration', true);
const showtimeId = Number(__ENV.K6_SHOWTIME_ID || 0);

export function setup() {
  if (!showtimeId) fail('K6_SHOWTIME_ID is required for the seat-map benchmark.');
}

export default function () {
  get(`/api/showtimes/${showtimeId}/seats`, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-seat-map-query', ['seat_map_duration']);
}
