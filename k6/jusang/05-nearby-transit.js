import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LAT = __ENV.LAT || '37.5665';
const LON = __ENV.LON || '126.9780';
const ADDRESS = __ENV.ADDRESS || '';

const VUS = Number(__ENV.VUS || 5);
const ITERATIONS = Number(__ENV.ITERATIONS || 20);

export const options = {
    scenarios: {
        nearby_transit: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: __ENV.MAX_DURATION || '10m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

export default function () {
    // 현재 main의 TheaterController는 sort 파라미터를 받지 않는다.
    // TRANSIT 캐시 구현/컨트롤러 확장 후에 실행할 스크립트다.
    const url =
        `${BASE_URL}/api/theaters/nearby` +
        `?latitude=${encodeURIComponent(LAT)}` +
        `&longitude=${encodeURIComponent(LON)}` +
        `&sort=TRANSIT` +
        (ADDRESS ? `&address=${encodeURIComponent(ADDRESS)}` : '');

    const res = http.get(url, {
        tags: { cache_test: '05-nearby', endpoint: 'nearby-transit' },
        timeout: __ENV.HTTP_TIMEOUT || '180s',
    });

    check(res, {
        'nearby transit response received': (r) => r.status >= 200 && r.status < 500,
    });

    sleep(Number(__ENV.SLEEP || 0.2));
}
