import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const manifestPath = !__ENV.MANIFEST
  ? '../../.local/booking-lock-benchmark/manifest.json'
  : (/^(?:[A-Za-z]:[\\/]|\/|\\\\)/.test(__ENV.MANIFEST)
      ? __ENV.MANIFEST
      : '../../' + __ENV.MANIFEST.replace(/^(?:\.\/|\.\\)/, ''));
const manifest = JSON.parse(open(manifestPath));
http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));
const users = manifest.users || [];
if (!users.length) throw new Error('Manifest에 users가 없습니다.');
const success = new Counter('smart_hold_success');
const conflict = new Counter('smart_hold_conflict');
const other = new Counter('smart_hold_other_http');
const transport = new Counter('smart_hold_transport_error');
const statusCounts = new Counter('smart_hold_http_status');

export const options = {
  scenarios: {
    one_request_per_group: {
      executor: 'per-vu-iterations',
      vus: users.length,
      iterations: 1,
      maxDuration: '3m',
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<1'],
  },
};

export default function () {
  const user = users[__VU - 1];
  if (!user) throw new Error(`VU ${__VU}에 대응하는 그룹이 없습니다.`);
  const response = http.post(
    `${manifest.baseUrl}/api/booking-groups/${user.groupId}/smart-hold`,
    null,
    { headers: { Authorization: `Bearer ${user.token}`, 'Idempotency-Key': `lock-bench-${__VU}-${__ITER}` },
      tags: { name: 'smart_hold' }, timeout: '60s' }
  );
  statusCounts.add(1, { status: String(response.status) });
  if (response.status === 0) transport.add(1);
  else if (response.status >= 200 && response.status < 300) success.add(1);
  else if (response.status === 409) conflict.add(1);
  else other.add(1);

  if (![200, 201, 202, 409].includes(response.status)) {
    console.error(
      `Unexpected smart-hold response: VU=${__VU} GROUP=${user.groupId} STATUS=${response.status} BODY=${String(response.body || '').slice(0, 500)}`
    );
  }
  check(response, {
    'HTTP response received': r => r.status > 0,
    'status is expected for a contention run': r => [200, 201, 202, 409].includes(r.status),
  });
}

export function handleSummary(data) {
  const out = __ENV.OUT || 'lock-benchmark-summary.json';
  return {
    [out]: JSON.stringify({
      label: __ENV.LABEL || 'unnamed',
      lockMode: __ENV.LOCK_MODE || 'unspecified',
      startedAt: new Date().toISOString(),
      showtimeId: manifest.showtimeId,
      movieId: manifest.movieId,
      viewingDate: manifest.viewingDate,
      partySize: manifest.partySize,
      vus: users.length,
      metrics: data.metrics,
    }, null, 2),
    stdout: `\nLock benchmark complete. label=${__ENV.LABEL || 'unnamed'}, vus=${users.length}, summary=${out}\n`,
  };
}
