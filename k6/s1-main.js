import http from 'k6/http';
import { check, sleep } from 'k6';

// 실행할 때 -e 로 넘기는 값 (안 넘기면 기본값 사용)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'spike';            // spike: 한순간에 몰림 / step: 계단식
const USERS = Number(__ENV.USERS || 500);       // spike 인원
const SPREAD = Number(__ENV.SPREAD || 30);      // spike: 이 시간(초) 안에 각자 랜덤하게 시작

// 계단식: 단계마다 15초 동안 올리고 1분 유지
const STEPS = [500, 1000, 2000, 3000, 4000, 5000];
const stepStages = STEPS.flatMap((target) => [
    { duration: '15s', target },
    { duration: '1m', target },
]).concat([{ duration: '10s', target: 0 }]);

const scenarios = {
    spike: {
        executor: 'per-vu-iterations', // 사람마다 딱 1번
        vus: USERS,
        iterations: 1,
        maxDuration: '5m',
    },
    step: {
        executor: 'ramping-vus',       // 인원을 단계적으로 늘림
        startVUs: 0,
        stages: stepStages,
        gracefulRampDown: '30s',
    },
};

export const options = {
    discardResponseBodies: true, // 응답 내용은 버려서 k6 메모리 절약
    scenarios: { [MODE]: scenarios[MODE] },
    thresholds: {
        // 계단식: 처음 1분 이후 누적 실패율이 10% 이상이면 자동 중단
        http_req_failed: MODE === 'step'
            ? [{ threshold: 'rate<0.10', abortOnFail: true, delayAbortEval: '1m' }]
            : ['rate<0.01'],
        // API별 p95를 결과 화면에 따로 보여주기 위한 기준
        'http_req_duration{api:main}': ['p(95)<1000'],
        'http_req_duration{api:movies}': ['p(95)<1000'],
        'http_req_duration{api:theaters}': ['p(95)<1000'],
    },
};

function get(path, api) {
    const res = http.get(`${BASE_URL}${path}`, { tags: { api }, timeout: '60s' });
    check(res, { [`${api} 200`]: (r) => r.status === 200 });
}

export default function () {
    // spike: SPREAD초 안에서 각자 랜덤한 순간에 메인 화면 접속
    if (MODE === 'spike') sleep(Math.random() * SPREAD);

    get('/api/main', 'main');
    sleep(1 + Math.random());                    // 화면 보는 시간 1~2초
    get('/api/movies?page=0&size=20', 'movies');
    sleep(1 + Math.random());
    get('/api/theaters?page=0&size=20', 'theaters');

    // step: 같은 사람이 다시 둘러보기 전 1~3초 쉼
    if (MODE === 'step') sleep(1 + Math.random() * 2);
}