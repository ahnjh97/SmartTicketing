import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();
const duration = new Trend('movies_query_duration', true);

export default function () {
  get('/api/movies?page=0&size=20', duration);
}

export function handleSummary(data) {
  return writeSummary(data, '01-movies-query', ['movies_query_duration']);
}
