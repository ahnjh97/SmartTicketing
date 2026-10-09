import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const fixturePath = __ENV.FIXTURE;
if (!fixturePath) fail('Internal setup error: FIXTURE was not set by run.ps1.');

const users = new SharedArray('waiting-rank-users', () => {
  const parsed = JSON.parse(open(fixturePath));
  if (!Array.isArray(parsed.users) || parsed.users.length === 0) {
    throw new Error('Local fixture must contain at least one test user.');
  }
  parsed.users.forEach((u, i) => {
    if (!u.loginId || !u.password) {
      throw new Error('fixture users[' + i + '] requires loginId/password.');
    }
  });
  return parsed.users;
});

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 20);
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
  const authenticated = users.map((user, i) => {
    const res = http.post(baseUrl + '/api/auth/login',
      JSON.stringify({ loginId: user.loginId, password: user.password }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'benchmark_login' } });
    let body = null;
    try { body = res.json(); } catch (_) {}
    if (res.status !== 200 || !body || !body.accessToken) {
      fail('Local test account login failed (HTTP ' + res.status + '): ' +
        String(res.body).slice(0, 500) +
        '. First verify this account can log in at http://localhost:5173.');
    }
    return { token: body.accessToken };
  });

  // Prefer active groups, but fall back to the user's booking history. The active
  // endpoint intentionally excludes completed/expired groups, even though their
  // waiting-queues read endpoint remains valid for a read-only benchmark.
  const user = authenticated[0];
  const active = http.get(baseUrl + '/api/booking-groups/active', {
    headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
    tags: { name: 'benchmark_discover_groups' },
  });
  let groups = null;
  try { groups = active.json(); } catch (_) {}
  if (active.status !== 200 || !Array.isArray(groups)) {
    fail('Could not discover active booking groups (HTTP ' + active.status + '): ' +
      String(active.body).slice(0, 500));
  }

  const groupIds = groups
    .map(group => Number(group.id))
    .filter(id => Number.isSafeInteger(id) && id > 0);

  // Active list can legitimately be empty after a restart or after all requests
  // have completed. Use the normal booking history endpoint before giving up.
  if (groupIds.length === 0) {
    const history = http.get(baseUrl + '/api/booking-groups', {
      headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
      tags: { name: 'benchmark_discover_history' },
    });
    let page = null;
    try { page = history.json(); } catch (_) {}
    if (history.status !== 200 || !page || !Array.isArray(page.items)) {
      fail('Could not discover booking history (HTTP ' + history.status + '): ' +
        String(history.body).slice(0, 500));
    }
    for (const group of page.items) {
      const id = Number(group.id);
      if (Number.isSafeInteger(id) && id > 0 && !groupIds.includes(id)) groupIds.push(id);
    }
  }

  // Also include the dedicated activity history. It exposes groupId (not id) and
  // is distinct from the recovery list, which only contains recoverable records.
  const historyActivity = http.get(baseUrl + '/api/booking-groups/history', {
    headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
    tags: { name: 'benchmark_discover_activity_history' },
  });
  let activityPage = null;
  try { activityPage = historyActivity.json(); } catch (_) {}
  if (historyActivity.status === 200 && activityPage && Array.isArray(activityPage.items)) {
    for (const item of activityPage.items) {
      const id = Number(item.groupId);
      if (Number.isSafeInteger(id) && id > 0 && !groupIds.includes(id)) groupIds.push(id);
    }
  }

  const candidates = [];
  const probeFailures = [];
  for (const groupId of groupIds) {
    const probe = http.get(baseUrl + '/api/booking-groups/' + groupId + '/waiting-queues', {
      headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
      tags: { name: 'benchmark_discover_queue' },
    });
    let data = null;
    try { data = probe.json(); } catch (_) {}
    if (probe.status === 200 && data && Array.isArray(data.items)) {
      candidates.push({ groupId, items: data.items });
      // Prefer a group with actual waiting rows, but an empty response is still
      // a valid endpoint target for measuring the read path.
      if (data.items.length > 0) break;
    } else {
      probeFailures.push({ groupId, status: probe.status, body: String(probe.body).slice(0, 180) });
    }
  }
  // If active IDs existed but none was readable, retry against history too.
  // This handles stale/terminal entries returned by the active projection.
  if (!candidates.length) {
    const history = http.get(baseUrl + '/api/booking-groups', {
      headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
      tags: { name: 'benchmark_discover_history' },
    });
    let page = null;
    try { page = history.json(); } catch (_) {}
    if (history.status === 200 && page && Array.isArray(page.items)) {
      for (const group of page.items) {
        const groupId = Number(group.id);
        if (!Number.isSafeInteger(groupId) || groupId <= 0) continue;
        const probe = http.get(baseUrl + '/api/booking-groups/' + groupId + '/waiting-queues', {
          headers: { Authorization: 'Bearer ' + user.token }, timeout: '15s',
          tags: { name: 'benchmark_discover_queue_history' },
        });
        let data = null;
        try { data = probe.json(); } catch (_) {}
        if (probe.status === 200 && data && Array.isArray(data.items)) {
          candidates.push({ groupId, items: data.items });
          if (data.items.length > 0) break;
        }
      }
    }
  }
  if (!candidates.length) {
    fail('No readable booking group was found for the logged-in local account. active=' + groupIds.length + ', activityHistoryHTTP=' + historyActivity.status + ', activityHistoryBody=' + String(historyActivity.body).slice(0, 300) + ', queueProbeFailures=' + JSON.stringify(probeFailures.slice(0, 5)) + '. The fixture account must own at least one booking group.');
  }
  const selected = candidates.find(c => c.items.length > 0) || candidates[0];
  return { users: authenticated, groupId: selected.groupId, discoveredItems: selected.items.length };
}

export default function (data) {
  const user = data.users[(__VU - 1 + __ITER * 17) % data.users.length];
  const res = http.get(baseUrl + '/api/booking-groups/' + data.groupId + '/waiting-queues', {
    headers: { Authorization: 'Bearer ' + user.token },
    timeout: __ENV.REQUEST_TIMEOUT || '15s',
    tags: { name: 'waiting_queues_read' },
  });

  latency.add(res.timings.duration);
  let valid = res.status === 200;
  let body = null;
  if (valid) {
    try { body = res.json(); } catch (_) { valid = false; }
  }
  if (valid) valid = !!body && Array.isArray(body.items) &&
    body.items.every(item => Number.isFinite(Number(item.aheadCount)));
  validRate.add(valid);
  if (!valid) {
    invalid.add(1);
    if (__ITER < 2) console.error('Invalid waiting response: VU=' + __VU +
      ', status=' + res.status + ', body=' + String(res.body).slice(0, 300));
  }
  check(res, {
    'waiting queue endpoint returns HTTP 200': r => r.status === 200,
    'response contains a valid items array': () => valid,
  });
}

export function handleSummary(data) {
  const outPath = __ENV.SUMMARY || 'waiting-rank-summary.json';
  const result = {
    benchmark: {
      type: 'read-only waiting queue rank API',
      branch: __ENV.BRANCH || 'not-specified',
      baseUrl, vus, duration,
      generatedAt: new Date().toISOString(),
      note: 'Measures GET /api/booking-groups/{groupId}/waiting-queues; group ID is auto-discovered. Does not directly measure async Outbox delivery delay.',
    },
    metrics: data.metrics,
    state: data.state,
  };
  return {
    [outPath]: JSON.stringify(result, null, 2),
    stdout: [
      'branch=' + result.benchmark.branch,
      'groupId=auto-discovered',
      'VUs=' + vus + ' duration=' + duration,
      'avg=' + (data.metrics.waiting_read_duration?.values?.avg ?? 'N/A') + ' ms',
      'p95=' + (data.metrics.waiting_read_duration?.values?.['p(95)'] ?? 'N/A') + ' ms',
      'p99=' + (data.metrics.waiting_read_duration?.values?.['p(99)'] ?? 'N/A') + ' ms',
      'req/s=' + (data.metrics.http_reqs?.values?.rate ?? 'N/A'),
      'validRate=' + (data.metrics.waiting_response_valid?.values?.rate ?? 'N/A'),
      'invalid=' + (data.metrics.waiting_response_invalid?.values?.count ?? 0),
      'summary=' + outPath,
    ].join(' | ') + '\\n',
  };
}
