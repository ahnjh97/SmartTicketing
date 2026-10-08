import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LAT = __ENV.LAT || '37.5665';
const LON = __ENV.LON || '126.9780';
const ADDRESS = __ENV.ADDRESS || '';

const VUS = Number(__ENV.VUS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
    scenarios: {
        nearby_distance: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: __ENV.MAX_DURATION || '5m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const url =
        `${BASE_URL}/api/theaters/nearby` +
        `?latitude=${encodeURIComponent(LAT)}` +
        `&longitude=${encodeURIComponent(LON)}` +
        (ADDRESS ? `&address=${encodeURIComponent(ADDRESS)}` : '');

    const res = http.get(url, {
        tags: { cache_test: '05-nearby', endpoint: 'nearby-distance' },
        timeout: __ENV.HTTP_TIMEOUT || '60s',
    });

    check(res, {
        'nearby distance status 200': (r) => r.status === 200,
        'nearby distance body exists': (r) => r.body && r.body.length > 0,
    });

    sleep(Number(__ENV.SLEEP || 0.02));
}
