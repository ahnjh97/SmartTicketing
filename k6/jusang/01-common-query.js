import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
    scenarios: {
        common_reads: {
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

const MOVIE_PAGE = __ENV.MOVIE_PAGE || '0';
const MOVIE_SIZE = __ENV.MOVIE_SIZE || '20';
const THEATER_PAGE = __ENV.THEATER_PAGE || '0';
const THEATER_SIZE = __ENV.THEATER_SIZE || '20';

export default function () {
    const n = __ITER % 10;

    let url;
    let name;

    if (n < 3) {
        url = `${BASE_URL}/api/main`;
        name = 'main';
    } else if (n < 7) {
        url = `${BASE_URL}/api/movies?page=${MOVIE_PAGE}&size=${MOVIE_SIZE}`;
        name = 'movies';
    } else {
        url = `${BASE_URL}/api/theaters?page=${THEATER_PAGE}&size=${THEATER_SIZE}`;
        name = 'theaters';
    }

    const res = http.get(url, {
        tags: { cache_test: '01-common', endpoint: name },
    });

    check(res, {
        [`${name} status 200`]: (r) => r.status === 200,
        [`${name} response body`]: (r) => r.body && r.body.length > 0,
    });

    sleep(Number(__ENV.SLEEP || 0.01));
}
