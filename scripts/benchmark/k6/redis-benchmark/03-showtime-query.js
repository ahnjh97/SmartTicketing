import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();

const duration = new Trend('showtime_query_duration', true);
const movieId = Number(__ENV.K6_MOVIE_ID || 1);
const theaterId = __ENV.K6_THEATER_ID ? Number(__ENV.K6_THEATER_ID) : null;
const date = __ENV.K6_DATE || new Date(Date.now() + 86400000).toISOString().slice(0, 10);

export default function () {
  const query = theaterId
    ? `/api/showtimes?movieId=${movieId}&theaterId=${theaterId}&date=${date}`
    : `/api/showtimes?movieId=${movieId}&date=${date}`;
  get(query, duration);
}

export function handleSummary(data) {
  return writeSummary(data, '03-showtime-query', ['showtime_query_duration']);
}
