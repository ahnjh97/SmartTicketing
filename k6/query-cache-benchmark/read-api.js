import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 20);
const duration = __ENV.DURATION || '30s';
const label = __ENV.LABEL || 'unnamed';
const cacheMode = __ENV.CACHE_MODE || 'unspecified';

if (!Number.isInteger(vus) || vus < 1 || vus > 100) {
  throw new Error('VUS must be an integer between 1 and 100');
}

http.setResponseCallback(http.expectedStatuses(200));

export const options = {
  scenarios: {
    repeated_read_queries: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

const endpoints = [
  { path: '/api/movies?page=0&size=20', name: 'movies_catalog' },
  { path: '/api/theaters?page=0&size=20', name: 'theaters_catalog' },
];

export default function () {
  for (const endpoint of endpoints) {
    const response = http.get(baseUrl + endpoint.path, {
      tags: { name: endpoint.name },
      timeout: '10s',
    });
    check(response, {
      [endpoint.name + ' returns HTTP 200']: r => r.status === 200,
      [endpoint.name + ' returns a body']: r => Boolean(r.body && r.body.length > 0),
    });
  }
  sleep(0.1);
}

export function handleSummary(data) {
  const out = __ENV.OUT || '.local/query-cache-benchmark/summary.json';
  return {
    [out]: JSON.stringify({
      label,
      cacheMode,
      startedAt: new Date().toISOString(),
      baseUrl,
      vus,
      duration,
      endpoints: endpoints.map(endpoint => endpoint.path),
      metrics: data.metrics,
    }, null, 2),
    stdout: `\nRead query cache benchmark complete. label=${label}, cacheMode=${cacheMode}, vus=${vus}, summary=${out}\n`,
  };
}
