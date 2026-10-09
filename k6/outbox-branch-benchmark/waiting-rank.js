import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const fixturePath = __ENV.FIXTURE;
if (!fixturePath) fail('Set FIXTURE to a local JSON fixture path.');

const users = new SharedArray('waiting-rank-users', () => {
  const parsed = JSON.parse(open(fixturePath));
  if (!Array.isArray(parsed.users) || parsed.users.length === 0) {
    throw new Error('Fixture must contain a non-empty users array.');
  }
  parsed.users.forEach((u, i) => {
    if (!u.loginId || !u.password || !Number.isSafeInteger(Number(u.groupId)) ||
        Number(u.groupId) <= 0 || !Number.isSafeInteger(Number(u.expectedAheadCount)) ||
        Number(u.expectedAheadCount) < 0) {
      throw new Error('Invalid fixture users[' + i + ']. Check loginId/password/groupId/expectedAheadCount.');
    }
  });
  return parsed.users;
});

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 10);
const duration = __ENV.DURATION || '60s';
const latency = new Trend('waiting_read_duration', true);
const validRate = new Rate('waiting_response_valid');
const invalid = new Counter('waiting_response_invalid');

export const options = {
  scenarios: {
    waiting_rank_read: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    waiting_response_valid: ['rate>0.99'],
    waiting_response_invalid: ['count==0'],
  },
};

export function setup() {
  return users.map((user, i) => {
    const res = http.post(
      baseUrl + '/api/auth/login',
      JSON.stringify({ loginId: user.loginId, password: user.password }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'benchmark_login' } },
    );
    let body = null;
    try { body = res.json(); } catch (_) {}
    if (res.status !== 200 || !body || !body.accessToken) {
      fail('Login failed for fixture user index ' + i + ', HTTP ' + res.status + '. Check test credentials.');
    }
    return {
      groupId: Number(user.groupId),
      expectedAheadCount: Number(user.expectedAheadCount),
      token: body.accessToken,
    };
  });
}

export default function (authenticatedUsers) {
  const user = authenticatedUsers[(__VU - 1 + __ITER * 17) % authenticatedUsers.length];
  const res = http.get(
    baseUrl + '/api/booking-groups/' + user.groupId + '/waiting-queues',
    { headers: { Authorization: 'Bearer ' + user.token }, timeout: __ENV.REQUEST_TIMEOUT || '15s',
      tags: { name: 'waiting_queues_read' } },
  );

  latency.add(res.timings.duration);
  let valid = res.status === 200;
  let body = null;
  if (valid) {
    try { body = res.json(); } catch (_) { valid = false; }
  }
  if (valid) {
    const items = body && Array.isArray(body.items) ? body.items : null;
    valid = !!items && items.length === 1 &&
      Number(items[0].aheadCount) === user.expectedAheadCount;
  }
  validRate.add(valid);
  if (!valid) {
    invalid.add(1);
    if (__ITER < 2) console.error('Invalid waiting response: VU=' + __VU + ', status=' + res.status +
      ', body=' + String(res.body).slice(0, 250));
  }
  check(res, {
    'waiting queue endpoint returns HTTP 200': r => r.status === 200,
    'queue item count and aheadCount match fixture': () => valid,
  });
}

export function handleSummary(data) {
  const outPath = __ENV.SUMMARY || 'waiting-rank-summary.json';
  const result = {
    benchmark: {
      type: 'read-only waiting queue rank API',
      branch: __ENV.BRANCH || 'not-specified',
      baseUrl,
      vus,
      duration,
      fixtureUsers: users.length,
      generatedAt: new Date().toISOString(),
      note: 'Measures GET /api/booking-groups/{groupId}/waiting-queues. It does not directly measure asynchronous Outbox delivery delay.',
    },
    metrics: data.metrics,
    state: data.state,
  };
  return {
    [outPath]: JSON.stringify(result, null, 2),
    stdout: [
      'branch=' + result.benchmark.branch,
      'VUs=' + vus + ' duration=' + duration + ' fixtureUsers=' + users.length,
      'avg=' + (data.metrics.waiting_read_duration?.values?.avg ?? 'N/A') + ' ms',
      'p95=' + (data.metrics.waiting_read_duration?.values?.['p(95)'] ?? 'N/A') + ' ms',
      'p99=' + (data.metrics.waiting_read_duration?.values?.['p(99)'] ?? 'N/A') + ' ms',
      'req/s=' + (data.metrics.http_reqs?.values?.rate ?? 'N/A'),
      'validRate=' + (data.metrics.waiting_response_valid?.values?.rate ?? 'N/A'),
      'invalid=' + (data.metrics.waiting_response_invalid?.values?.count ?? 0),
      'summary=' + outPath,
    ].join(' | ') + '\n',
  };
}
