import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 5000); // 동시에 로그인 버튼을 누르는 사람 수

export const options = {
    discardResponseBodies: true, // 응답 내용은 버려서 k6 메모리 절약
    scenarios: {
        spike: {
            executor: 'per-vu-iterations', // 사람마다 딱 1번 로그인
            vus: USERS,                    // USERS명이 동시에 시작
            iterations: 1,
            maxDuration: '3m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],     // 실패 1% 미만
        http_req_duration: ['p(95)<3000'],  // 95%가 3초 안에 로그인
    },
};

export default function () {
    // SPREAD초 안에서 각자 랜덤한 순간에 로그인 버튼을 누름 (기본 0초 = 완전 동시)
    sleep(Math.random() * Number(__ENV.SPREAD || 45));
    const res = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ loginId: __ENV.LOGIN_ID, password: __ENV.PASSWORD }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '120s' }
    );
    check(res, { '로그인 200': (r) => r.status === 200 });
}