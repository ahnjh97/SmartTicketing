import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 100);

export const options = {
    scenarios: {
        catalog_reads: {
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
    const moviePage = __ITER % Number(__ENV.MOVIE_PAGES || 3);
    const theaterPage = __ITER % Number(__ENV.THEATER_PAGES || 3);

    const movie = http.get(
        `${BASE_URL}/api/movies?page=${moviePage}&size=${__ENV.SIZE || 20}`,
        { tags: { cache_test: '01-catalog', endpoint: 'movies' } }
    );

    check(movie, {
        'movies status 200': (r) => r.status === 200,
    });

    const theater = http.get(
        `${BASE_URL}/api/theaters?page=${theaterPage}&size=${__ENV.SIZE || 20}`,
        { tags: { cache_test: '01-catalog', endpoint: 'theaters' } }
    );

    check(theater, {
        'theaters status 200': (r) => r.status === 200,
    });

    sleep(Number(__ENV.SLEEP || 0.01));
}
