import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHOWTIME_ID = __ENV.SHOWTIME_ID || '115260';

const VUS = Number(__ENV.VUS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
    scenarios: {
        seat_map_reads: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: __ENV.MAX_DURATION || '3m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const id = __ENV.SHOWTIME_IDS
        ? __ENV.SHOWTIME_IDS.split(',')[__ITER % __ENV.SHOWTIME_IDS.split(',').length]
        : SHOWTIME_ID;

    const res = http.get(`${BASE_URL}/api/showtimes/${id}/seats`, {
        tags: { cache_test: '03-seat-map', endpoint: 'seat-map' },
    });

    check(res, {
        'seat map status 200': (r) => r.status === 200,
        'seat map body exists': (r) => r.body && r.body.length > 0,
    });

    sleep(Number(__ENV.SLEEP || 0.01));
}
