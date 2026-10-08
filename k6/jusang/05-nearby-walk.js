

function loginForBenchmark() {
    if (!LOGIN_ID || !PASSWORD) {
        throw new Error('nearby k6 test requires LOGIN_ID and PASSWORD environment variables');
    }

    const login = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ loginId: LOGIN_ID, password: PASSWORD }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (login.status !== 200) {
        throw new Error(`nearby login failed: status=${login.status}`);
    }

    return `Bearer ${login.json().accessToken}`;
}

export function setup() {
    return { authorization: loginForBenchmark() };
}import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LAT = __ENV.LAT || '37.5665';
const LON = __ENV.LON || '126.9780';
const ADDRESS = __ENV.ADDRESS || '';
const LOGIN_ID = __ENV.LOGIN_ID || '';
const PASSWORD = __ENV.PASSWORD || '';

const VUS = Number(__ENV.VUS || 5);
const ITERATIONS = Number(__ENV.ITERATIONS || 20);

export const options = {
    scenarios: {
        nearby_walk: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: ITERATIONS,
            maxDuration: __ENV.MAX_DURATION || '5m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

export default function (data) {
    // 현재 main의 TheaterController는 sort 파라미터를 받지 않는다.
    // WALK 캐시 구현/컨트롤러 확장 후에 실행할 스크립트다.
    const url =
        `${BASE_URL}/api/theaters/nearby` +
        `?latitude=${encodeURIComponent(LAT)}` +
        `&longitude=${encodeURIComponent(LON)}` +
        `&sort=WALK` +
        (ADDRESS ? `&address=${encodeURIComponent(ADDRESS)}` : '');

    const res = http.get(url, {
        tags: { cache_test: '05-nearby', endpoint: 'nearby-walk' },
        timeout: __ENV.HTTP_TIMEOUT || '120s',
    });

    check(res, {
        'nearby walk response received': (r) => r.status >= 200 && r.status < 500,
    });

    sleep(Number(__ENV.SLEEP || 0.1));
}
