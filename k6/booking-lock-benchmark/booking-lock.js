import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const fixturePath = __ENV.FIXTURE;
if (!fixturePath) fail('FIXTURE 환경변수에 테스트 케이스 JSON 경로를 지정하세요.');
const cases = new SharedArray('booking-lock-cases', () => {
  const data = JSON.parse(open(fixturePath));
  if (!Array.isArray(data.cases) || data.cases.length === 0) {
    throw new Error('fixture JSON에는 비어 있지 않은 cases 배열이 필요합니다.');
  }
  return data.cases;
});

const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 10);
const iterations = Number(__ENV.ITERATIONS || 10);
const requestDuration = new Trend('booking_request_duration', true);
const created = new Counter('booking_created');
const conflict = new Counter('booking_conflict');
const unexpected = new Counter('booking_unexpected');
const validOutcome = new Rate('booking_valid_outcome');

export const options = {
  scenarios: {
    booking_lock_load: {
      executor: 'per-vu-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '10m',
      gracefulStop: '0s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '5m',
  thresholds: {},
};

export function setup() {
  const tokens = new Map();
  for (const item of cases) {
    if (!item.loginId || !item.password) {
      fail('fixture 케이스마다 loginId/password를 지정하세요.');
    }
    if (!tokens.has(item.loginId)) {
      const login = http.post(
        `${baseUrl}/api/auth/login`,
        JSON.stringify({ loginId: item.loginId, password: item.password }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '30s', tags: { name: 'benchmark-login' } },
      );
      let data;
      try { data = login.json(); } catch { data = null; }
      if (login.status !== 200 || !data?.accessToken) {
        fail(`테스트 계정 로그인 실패: loginId=${item.loginId}, status=${login.status}`);
      }
      tokens.set(item.loginId, data.accessToken);
    }
  }
  return cases.map(item => ({ ...item, token: tokens.get(item.loginId) }));
}

export default function (authenticatedCases) {
  const index = (__VU - 1) * iterations + __ITER;
  const item = authenticatedCases[index];
  if (!item) {
    fail(`fixture 케이스 부족: 필요한 index=${index}, cases.length=${cases.length}`);
  }
  if (!item.token || !Number.isSafeInteger(Number(item.groupId)) || Number(item.groupId) <= 0
      || !Array.isArray(item.seatIds) || item.seatIds.length < 1
      || item.seatIds.some(id => !Number.isSafeInteger(Number(id)) || Number(id) <= 0)) {
    fail(`잘못된 fixture 케이스 index=${index}: token, groupId, seatIds를 확인하세요.`);
  }

  const token = String(item.token).replace(/^Bearer\\s+/i, '');
  const body = JSON.stringify({ seatIds: item.seatIds.map(Number) });
  const response = http.post(
    `${baseUrl}/api/booking-groups/${item.groupId}/manual-hold`,
    body,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': item.idempotencyKey || `k6-lock-${__VU}-${__ITER}-${Date.now()}`,
      },
      timeout: __ENV.REQUEST_TIMEOUT || '30s',
      tags: { name: 'manual-hold' },
    },
  );

  requestDuration.add(response.timings.duration);
  if (response.status === 201) {
    created.add(1);
    validOutcome.add(true);
  } else if (response.status === 409) {
    conflict.add(1);
    validOutcome.add(true);
  } else {
    unexpected.add(1);
    validOutcome.add(false);
  }

  check(response, {
    'response is expected booking outcome (201/409)': r => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  const path = __ENV.SUMMARY_FILE;
  const output = {};
  const summary = { ...data, benchmark: { baseUrl, vus, iterations, totalCases: vus * iterations } };
  if (path) output[path] = JSON.stringify(summary, null, 2);
  output.stdout = [
    `BASE_URL=${baseUrl}`,
    `VUs=${vus} iterations/VU=${iterations} total=${vus * iterations}`,
    `p95=${data.metrics.http_req_duration?.values?.['p(95)'] ?? 'n/a'} ms`,
    `p99=${data.metrics.http_req_duration?.values?.['p(99)'] ?? 'n/a'} ms`,
    `201=${data.metrics.booking_created?.values?.count ?? 0}`,
    `409=${data.metrics.booking_conflict?.values?.count ?? 0}`,
    `unexpected=${data.metrics.booking_unexpected?.values?.count ?? 0}`,
  ].join(' | ') + String.fromCharCode(10);
  return output;
}
