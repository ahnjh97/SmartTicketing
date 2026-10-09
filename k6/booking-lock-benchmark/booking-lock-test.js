
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LOGIN_ID = __ENV.LOGIN_ID;
const PASSWORD = __ENV.PASSWORD;
const GROUP_ID = __ENV.GROUP_ID;
const SEAT_IDS = (__ENV.SEAT_IDS || '')
    .split(',')
    .filter(Boolean)
    .map(Number);

const VUS = Number(__ENV.VUS || 20);

const success201 = new Counter('booking_success_201');
const conflict409 = new Counter('booking_conflict_409');
const otherStatus = new Counter('booking_other_status');

export const options = {
    scenarios: {
        seat_race: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '1m',
        },
    },
};

export function setup() {
    if (!LOGIN_ID || !PASSWORD || !GROUP_ID)  {
        throw new Error(
            'LOGIN_ID, PASSWORD, GROUP_ID, SEAT_IDS 환경변수를 설정하세요.'
        );
    }

    const response = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({
            loginId: LOGIN_ID,
            password: PASSWORD,
        }),
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'login' },
        }
    );

    check(response, {
        '로그인 성공': (r) => r.status === 200,
    });

    if (response.status !== 200) {
        throw new Error(`로그인 실패: HTTP ${response.status}`);
    }

    const body = response.json();
    const token = body.accessToken;

    if (!token) {
        throw new Error('로그인 응답에서 accessToken을 찾을 수 없습니다.');
    }

    return { token };
}

export default function (data) {
    const response = http.post(
        `${BASE_URL}/api/booking-groups/${GROUP_ID}/smart-hold`,
        JSON.stringify({}),
        {
            headers: {
                Authorization: `Bearer ${data.token}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}`,
            },
            tags: { name: 'manual_hold' },
        }
    );

    if (response.status === 201) {
        success201.add(1);
    } else if (response.status === 409) {
        conflict409.add(1);
    } else {
        otherStatus.add(1);
    }


    console.log(
        `VU=${__VU} status=${response.status} duration=${response.timings.duration.toFixed(2)}ms body=${response.body}`
    );

    check(response, {
        '예상된 응답(201 또는 409)': (r) =>
            r.status === 201 || r.status === 409,
    });
}