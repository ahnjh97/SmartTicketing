import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 10);

const holdSuccess = new Counter('hold_success');
const seatConflict = new Counter('seat_conflict');
const serverError = new Counter('server_error');

const BASE_URL = 'http://localhost:8080';
const LOGIN_ID = 'admin';
const PASSWORD = '11111111';

const MOVIE_ID = 11;
const SHOWTIME_ID = 64364;
const SEAT_ID = 2521;

export const options = {
    vus: VUS,
    iterations: VUS,
    maxDuration: '10m',
    setupTimeout: '5m',
};

export function setup() {
    const loginRes = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({
            loginId: LOGIN_ID,
            password: PASSWORD,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
            },
        }
    );

    check(loginRes, {
        'login status 200': (r) => r.status === 200,
        'accessToken exists': (r) => !!r.json('accessToken'),
    });

    if (loginRes.status !== 200) {
        throw new Error(
            `로그인 실패: ${loginRes.status} ${loginRes.body}`
        );
    }

    const token = loginRes.json('accessToken');
    const groups = [];

    console.log(`그룹 ${VUS}개 생성 시작`);

    for (let i = 0; i < VUS; i++) {
        const key = `k6-group-${Date.now()}-${i}`;

        const res = http.post(
            `${BASE_URL}/api/booking-groups`,
            JSON.stringify({
                movieId: MOVIE_ID,
                entryPoint: 'THEATER_NORMAL',
                partySize: 1,
                viewingDate: '2026-10-06',
                audience: {
                    youthCount: 0,
                    guardianAccompanying: true,
                    companionsEligible: true,
                    adultCount: 1,
                },
                selectedShowtimeId: SHOWTIME_ID,
            }),
            {
                headers: {
                    Authorization: `Bearer ${token}`,
                    'Content-Type': 'application/json',
                    'Idempotency-Key': key,
                },
            }
        );

        const success = check(res, {
            'group creation status 201': (r) => r.status === 201,
        });

        if (!success) {
            throw new Error(
                `그룹 생성 실패 index=${i}, status=${res.status}, body=${res.body}`
            );
        }

        groups.push(res.json('id'));

        if ((i + 1) % 100 === 0 || i === VUS - 1) {
            console.log(`그룹 생성: ${i + 1}/${VUS}`);
        }
    }

    console.log(`그룹 ${groups.length}개 생성 완료`);

    return {
        token,
        groups,
    };
}

export default function (data) {
    const groupId = data.groups[__VU - 1];

    if (!groupId) {
        throw new Error(
            `groupId를 찾을 수 없습니다. VU=${__VU}, groups=${data.groups.length}`
        );
    }

    const key = `k6-hold-${Date.now()}-${__VU}`;

    const res = http.post(
        `${BASE_URL}/api/booking-groups/${groupId}/manual-hold`,
        JSON.stringify({
            seatIds: [SEAT_ID],
        }),
        {
            headers: {
                Authorization: `Bearer ${data.token}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': key,
            },
        }
    );

    if (res.status === 201) {
        holdSuccess.add(1);
    } else if (res.status === 409) {
        seatConflict.add(1);
    } else if (res.status >= 500) {
        serverError.add(1);
    }

    check(res, {
        'hold response is 201 or 409': (r) =>
            r.status === 201 || r.status === 409,

        'no 5xx': (r) =>
            r.status < 500,
    });

    console.log(
        `VU=${__VU} group=${groupId} status=${res.status}`
    );
}
