import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    main_query_on: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.K6_RATE || 50),
      timeUnit: '1s',
      duration: __ENV.K6_DURATION || '30s',
      preAllocatedVUs: Number(__ENV.K6_PRE_ALLOCATED_VUS || 100),
      maxVUs: Number(__ENV.K6_MAX_VUS || 1000),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/api/main`, {
    headers: { Accept: 'application/json' },
    tags: { endpoint: 'main-query', cache: 'on' },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'response has body': (r) => !!r.body,
  });

  sleep(0.01);
}
