import { check } from 'k6';
import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const RATE = Number(__ENV.K6_RATE || 50);
export const DURATION = __ENV.K6_DURATION || '30s';
export const PRE_VUS = Number(__ENV.K6_PRE_VUS || 50);
export const MAX_VUS = Number(__ENV.K6_MAX_VUS || 200);

export function loadOptions() {
  return {
    scenarios: {
      read_load: {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        duration: DURATION,
        preAllocatedVUs: PRE_VUS,
        maxVUs: MAX_VUS,
      },
    },
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
    thresholds: { http_req_failed: ['rate<0.01'] },
  };
}

export function get(url, trend, params = {}) {
  const response = http.get(`${BASE_URL}${url}`, params);
  trend.add(response.timings.duration);
  check(response, { 'HTTP 200': (r) => r.status === 200 });
  return response;
}

export function writeSummary(data, feature, metricNames) {
  const metrics = {};
  for (const name of metricNames) {
    metrics[name] = data.metrics[name] ? data.metrics[name].values : null;
  }

  const summary = {
    schemaVersion: 1,
    feature,
    cacheMode: __ENV.CACHE_MODE || 'unknown',
    timestamp: new Date().toISOString(),
    target: BASE_URL,
    load: { rate: RATE, duration: DURATION, preAllocatedVUs: PRE_VUS, maxVUs: MAX_VUS },
    metrics: {
      httpReqDuration: data.metrics.http_req_duration?.values || null,
      httpReqFailed: data.metrics.http_req_failed?.values || null,
      httpReqs: data.metrics.http_reqs?.values || null,
      checks: data.metrics.checks?.values || null,
    },
    endpoints: metrics,
  };

  const file = __ENV.RESULT_FILE || `benchmark-results/${feature}-${summary.cacheMode}.json`;
  return {
    [file]: JSON.stringify(summary, null, 2),
    stdout: `Redis ${summary.cacheMode} | ${feature} | p95=${summary.metrics.httpReqDuration?.['p(95)'] ?? 'n/a'}ms | p99=${summary.metrics.httpReqDuration?.['p(99)'] ?? 'n/a'}ms\n`,
  };
}
