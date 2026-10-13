import { fail } from 'k6';
import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();

const duration = new Trend('seat_map_duration', true);
const configuredShowtimeId = Number(__ENV.K6_SHOWTIME_ID || 0);

export function setup() {
  if (!configuredShowtimeId) {
    fail('Seat-map benchmark requires K6_SHOWTIME_ID. Pass -ShowtimeId <id> to run-cache-comparison.ps1.');
  }

  // Do not call the API from setup(). The seat-map endpoint itself is the
  // benchmark target and can legitimately be slow under the OFF comparison.
  // Validating it here caused k6's 60-second setup timeout to abort the test
  // before the actual load phase started.
  return { showtimeId: configuredShowtimeId };
}

export default function (data) {
  get(`/api/showtimes/${data.showtimeId}/seats`, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-seat-map-query', ['seat_map_duration']);
}
