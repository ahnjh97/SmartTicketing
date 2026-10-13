import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();
const duration = new Trend('theaters_query_duration', true);

export default function () {
  get('/api/theaters?page=0&size=20', duration);
}

export function handleSummary(data) {
  return writeSummary(data, '01-theaters-query', ['theaters_query_duration']);
}
