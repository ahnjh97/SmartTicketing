import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MOVIE_ID = __ENV.MOVIE_ID || '14';
const THEATER_ID = __ENV.THEATER_ID || '1';
const DATE = __ENV.DATE || '2026-10-09';

const VUS = Number(__ENV.VUS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
    scenarios: {
        showtime_reads: {
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
    const showtimesUrl =
        `${BASE_URL}/api/showtimes` +
        `?movieId=${encodeURIComponent(MOVIE_ID)}` +
        `&theaterId=${encodeURIComponent(THEATER_ID)}` +
        `&date=${encodeURIComponent(DATE)}`;

    const showtimes = http.get(showtimesUrl, {
        tags: { cache_test: '03-showtime', endpoint: 'showtimes' },
    });

    check(showtimes, {
        'showtimes status 200': (r) => r.status === 200,
        'showtimes body exists': (r) => r.body && r.body.length > 0,
    });

    const availabilityUrl =
        `${BASE_URL}/api/showtimes/availability` +
        `?movieId=${encodeURIComponent(MOVIE_ID)}` +
        `&date=${encodeURIComponent(DATE)}` +
        `&theaterIds=${encodeURIComponent(THEATER_ID)}`;

    const availability = http.get(availabilityUrl, {
        tags: { cache_test: '03-showtime', endpoint: 'availability' },
    });

    check(availability, {
        'availability status 200': (r) => r.status === 200,
        'availability body exists': (r) => r.body && r.body.length > 0,
    });

    sleep(Number(__ENV.SLEEP || 0.01));
}
