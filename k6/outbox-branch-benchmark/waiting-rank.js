import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

if (!__ENV.FIXTURE) fail('FIXTURE must point to a local credentials file.');
const users = new SharedArray('waiting-rank-users', () => {
  const rows = JSON.parse(open(__ENV.FIXTURE)).users;
  if (!Array.isArray(rows) || !rows.length || rows.some(u => !u.loginId || !u.password))
    throw new Error('Fixture requires users with loginId/password.');
  return rows;
});
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 20);
const duration = __ENV.DURATION || '60s';
const latency = new Trend('waiting_read_duration', true);
const requests = new Counter('waiting_read_requests');
const validRate = new Rate('waiting_response_valid');
const invalid = new Counter('waiting_response_invalid');
export const options = {
  scenarios: { waiting_rank_read: { executor: 'constant-vus', vus, duration, gracefulStop: '5s' } },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: { waiting_response_valid: ['rate>0.99'], waiting_response_invalid: ['count==0'] },
};
function json(response) { try { return response.json(); } catch (_) { return null; } }
function valid(body) {
  return body && Array.isArray(body.items) && body.items.length > 0 &&
    body.items.some(item => item.status === 'WAITING') &&
    body.items.every(item => typeof item.aheadCount === 'number' && Number.isInteger(item.aheadCount) && item.aheadCount >= 0);
}
export function setup() {
  const targets = users.map((user, index) => {
    const login = http.post(baseUrl + '/api/auth/login', JSON.stringify({loginId:user.loginId,password:user.password}),
      {headers:{'Content-Type':'application/json'},tags:{name:'benchmark_login'}});
    const body = json(login);
    if(login.status !== 200 || !body?.accessToken) fail('Local test account login failed (user index ' + index + ', HTTP ' + login.status + ').');
    const headers = {Authorization:'Bearer ' + body.accessToken};
    const active = http.get(baseUrl + '/api/booking-groups/active', {headers,tags:{name:'benchmark_discover_groups'}});
    const groups = json(active);
    if(active.status !== 200 || !Array.isArray(groups)) fail('Cannot discover active groups for user index ' + index);
    const ids = user.groupId ? [Number(user.groupId)] : groups.map(g => Number(g.id));
    for(const groupId of ids) {
      if(!Number.isSafeInteger(groupId) || groupId < 1) continue;
      const probe = http.get(baseUrl + '/api/booking-groups/' + groupId + '/waiting-queues', {headers,tags:{name:'benchmark_probe'}});
      const queue = json(probe);
      if(probe.status === 200 && valid(queue)) return {token:body.accessToken,groupId};
    }
    fail('User index ' + index + ' needs an owned group with active WAITING rows. Empty/terminal queues are not rank benchmarks.');
  });
  return {targets};
}
export default function(data) {
  const target = data.targets[(__VU - 1) % data.targets.length];
  const res = http.get(baseUrl + '/api/booking-groups/' + target.groupId + '/waiting-queues', {
    headers:{Authorization:'Bearer ' + target.token}, timeout:__ENV.REQUEST_TIMEOUT || '15s', tags:{name:'waiting_queues_read'},
  });
  requests.add(1);
  latency.add(res.timings.duration);
  const ok = res.status === 200 && valid(json(res));
  validRate.add(ok);
  invalid.add(ok ? 0 : 1);
  check(res, {'active waiting queue rank response': () => ok});
}
export function handleSummary(data) {
  const result = {
    benchmark:{type:'read-only active waiting rank API',branch:__ENV.BRANCH || 'unspecified',baseUrl,vus,duration,
      generatedAt:new Date().toISOString(),note:'No events are generated. This measures rank reads, not Outbox throughput or delivery latency.'},
    metrics:data.metrics,state:data.state,
  };
  return {
    [__ENV.SUMMARY || 'waiting-rank-summary.json']:JSON.stringify(result,null,2),
    stdout:'Rank-read requests/s=' + (data.metrics.waiting_read_requests?.values?.rate ?? 'N/A') +
      ' avg=' + (data.metrics.waiting_read_duration?.values?.avg ?? 'N/A') + 'ms; this is NOT an Outbox drain benchmark.\n',
  };
}
