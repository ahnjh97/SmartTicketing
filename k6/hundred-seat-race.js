import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const VUS = 1000;

const holdSuccess = new Counter('hold_success');
const seatConflict = new Counter('seat_conflict');
const serverError = new Counter('server_error');

const BASE_URL = 'http://localhost:8080';
const LOGIN_ID = 'admin';
const PASSWORD = '11111111';

const MOVIE_ID = 18;
const SHOWTIME_ID = 63662;

const SEAT_IDS = Array.from({ length: 100 }, (_, i) => 721 + i);

export const options = {
    scenarios: {
        hundredSeatRace: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '10m',
        },
    },

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
            `濡쒓렇???ㅽ뙣: ${loginRes.status} ${loginRes.body}`
        );
    }

    const token = loginRes.json('accessToken');
    const groups = [];

    console.log(`1000媛?洹몃９ ?앹꽦 ?쒖옉`);

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

        if (res.status !== 201) {
            throw new Error(
                `洹몃９ ?앹꽦 ?ㅽ뙣 index=${i}, status=${res.status}, body=${res.body}`
            );
        }

        groups.push(res.json('id'));

        if ((i + 1) % 100 === 0) {
            console.log(`洹몃９ ?앹꽦: ${i + 1}/${VUS}`);
        }
    }

    console.log(`洹몃９ ${groups.length}媛??앹꽦 ?꾨즺`);

    return {
        token,
        groups,
    };
}

export default function (data) {
    const index = __VU - 1;

    const groupId = data.groups[index];

    if (!groupId) {
        throw new Error(
            `groupId ?놁쓬: VU=${__VU}, index=${index}`
        );
    }

    // 100媛?醫뚯꽍??1000紐낆씠 寃쎌웳?섎룄濡?
    // VU 1~10 -> 泥?踰덉㎏ 醫뚯꽍
    // VU 11~20 -> ??踰덉㎏ 醫뚯꽍
    // ...
    // VU 991~1000 -> 100踰덉㎏ 醫뚯꽍
    const seatIndex = Math.floor(index / 10);
    const seatId = SEAT_IDS[seatIndex];

    if (!seatId) {
        throw new Error(
            `seatId ?놁쓬: VU=${__VU}, seatIndex=${seatIndex}`
        );
    }

    const key = `k6-hold-${Date.now()}-${__VU}`;

    const res = http.post(
        `${BASE_URL}/api/booking-groups/${groupId}/manual-hold`,
        JSON.stringify({
            seatIds: [seatId],
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
    } else {
        serverError.add(1);

        console.log(
            `REQUEST ERROR VU=${__VU} group=${groupId} seat=${seatId} status=${res.status} error=${res.error} errorCode=${res.error_code}`
        );
    }

    check(res, {
        'hold response is 201 or 409': (r) =>
            r.status === 201 || r.status === 409,

        'no 5xx': (r) => r.status < 500,

        'not connection refused': (r) => r.status !== 0,
    });

    console.log(
        `VU=${__VU} group=${groupId} seat=${seatId} status=${res.status}`
    );
}


