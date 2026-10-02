import test from 'node:test';
import assert from 'node:assert/strict';
import { availability, dates, seoulDate, validParty, validRange, bookingReturn, futureRange } from '../src/booking/state.js';
import { movieImage } from '../src/booking/images.js';
import { catalog } from '../src/api/booking.js';
import { theaterApi } from '../src/api/theaters.js';
test('Seoul calendar dates and seven-day horizon', () => {
    assert.equal(seoulDate(new Date('2026-10-01T15:00:00Z')), '2026-10-02');
    assert.equal(dates('2026-12-29')[6], '2027-01-04');
    assert.equal(dates().length, 7);
});
test('today bounds exclude the current and past minute, tomorrow and midnight crossing remain valid', () => {
    const now = Date.parse('2026-10-01T15:37:30+09:00');
    assert.equal(futureRange('2026-10-01', '15:37', '19:00', now), false);
    assert.equal(futureRange('2026-10-01', '16:00', '19:00', now), true);
    assert.equal(futureRange('2026-10-01', '23:30', '00:30', now), true);
    assert.equal(futureRange('2026-10-02', '00:00', '01:00', now), true);
    assert.equal(futureRange('2026-09-30', '23:00', '22:00', now), false);
    assert.equal(futureRange('2026-10-01', '23:59', '00:00', Date.parse('2026-10-01T23:59:01+09:00')), false);
});
test('hero uses TMDB original while cards request responsive thumbnails; other hosts are untouched', () => {
    const url = 'https://image.tmdb.org/t/p/w1280/film.jpg';
    assert.equal(movieImage(url, { hero: true }).src, 'https://image.tmdb.org/t/p/original/film.jpg');
    assert.ok(movieImage(url).srcSet.includes('w780/film.jpg 780w'));
    assert.deepEqual(movieImage('https://other.example/w500/film.jpg', { hero: true }), { src: 'https://other.example/w500/film.jpg' });
});
test('confirmed party limit and midnight time range', () => {
    for (const n of [1, 2, 3, 4, 5, 6]) assert.equal(validParty(n), true);
    for (const n of [0, -1, 7, 1.5, '', '1e2']) assert.equal(validParty(n), false);
    assert.equal(validRange('22:00', '02:00'), true);
    assert.equal(validRange('', ''), true);
    for (const pair of [['22:00', '22:00'], ['12:00', ''], ['24:00', '02:00']]) assert.equal(validRange(...pair), false);
});
test('legacy inventory without supported party sizes stays conservative', () => {
    const s = { layoutComplete: true, availableSeats: 63, maxContiguousSeats: 1 };
    assert.equal(availability(s, 2), '연속좌석 부족');
    assert.equal(availability(s, 4), '연속좌석 부족');
    assert.equal(availability({ ...s, maxContiguousSeats: 2 }, 4), '연속좌석 부족');
    assert.equal(availability({ ...s, maxContiguousSeats: 2 }, 5), '연속좌석 부족');
    assert.equal(availability({ ...s, maxContiguousSeats: 2 }, 6), '연속좌석 부족');
    assert.equal(availability({ ...s, maxContiguousSeats: 6 }, 6), '조회상 선택 가능');
    assert.equal(availability({ ...s, availableSeats: 0 }, 2), '매진');
    assert.equal(availability({ ...s, layoutComplete: false }, 2), '배치 미확인');
});

test('inventory reports supported split combinations without claiming every fragment works', () => {
    const show = { layoutComplete:true, availableSeats:6, maxContiguousSeats:2, bookablePartySizes:[1,2,4,6] };
    assert.equal(availability(show,4), '분할 착석 가능');
    assert.equal(availability(show,6), '분할 착석 가능');
    assert.equal(availability(show,5), '좌석 조합 부족');
    assert.equal(availability(show,2), '조회상 선택 가능');
    assert.equal(availability({ ...show, maxContiguousSeats:3, bookablePartySizes:[1,2,3,4,5,6] },5), '분할 착석 가능');
});
test('public API passes exact midnight bounds; nearby still requires auth', async () => {
    const original = { fetch: globalThis.fetch, storage: globalThis.localStorage };
    globalThis.localStorage = { getItem: () => null };
    const calls = [];
    globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response('{"items":[]}'); };
    try {
        await catalog('showtimes', { movieId: 41, date: '2026-10-01', startFrom: '22:00', startUntil: '02:00' });
        assert.equal(calls[0].url, '/api/showtimes?movieId=41&date=2026-10-01&startFrom=22%3A00&startUntil=02%3A00');
        assert.equal(calls[0].options.headers.Authorization, undefined);
        await assert.rejects(theaterApi.nearby({ latitude: 37, longitude: 127 }), { status: 401 });
        assert.equal(calls.length, 1);
    } finally { globalThis.fetch = original.fetch; globalThis.localStorage = original.storage; }
});
test('login return rejects external or unrelated URLs', () => {
    const previous = globalThis.sessionStorage;
    try {
        for (const url of ['https://evil.example', '//evil.example', '/profile', '/movies-evil']) {
            globalThis.sessionStorage = { getItem: () => url }; assert.equal(bookingReturn(), null);
        }
        globalThis.sessionStorage = { getItem: () => '/movies?movie=41&party=6' };
        assert.equal(bookingReturn(), '/movies?movie=41&party=6');
    } finally { globalThis.sessionStorage = previous; }
});
