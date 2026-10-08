import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = loadOptions();

const mainDuration = new Trend('common_main_duration', true);
const moviesDuration = new Trend('common_movies_duration', true);
const theatersDuration = new Trend('common_theaters_duration', true);

export default function () {
  switch (__ITER % 3) {
    case 0:
      get('/api/main', mainDuration);
      break;
    case 1:
      get('/api/movies?page=0&size=20', moviesDuration);
      break;
    default:
      get('/api/theaters?page=0&size=20', theatersDuration);
      break;
  }
}

export function handleSummary(data) {
  return writeSummary(data, '01-common-query-integrated', [
    'common_main_duration',
    'common_movies_duration',
    'common_theaters_duration',
  ]);
}
