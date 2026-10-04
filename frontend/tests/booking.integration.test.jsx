import { StrictMode } from 'react';
import { MemoryRouter, useNavigate, useLocation } from 'react-router-dom';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from '../src/App.jsx';
import { dates, seoulDate } from '../src/booking/state.js';
vi.mock('../src/maps/theaterMarkerImages.js', () => ({
    getTheaterBrand: theater => theater.brand,
    getTheaterMarkerImage: async brand => ({ brand }),
}));
vi.mock('../src/components/ResidencePreference.jsx', () => ({
    default: ({ user, onSaved }) => <button onClick={() => onSaved({ ...user, birthDate: '2000-01-01', address: '서울', preferredTheaters: [1, 2, 3].map(theaterId => ({ theaterId })), preferredSeats: [{ position: 'MIDDLE_MIDDLE', priority: 1 }] })}>선호 설정 완료</button>,
}));
const movie = { id: 41, title: '서울의 밤', rating: '12세', runningTime: 120, description: '영화 소개', posterUrl: '/poster.jpg', backdropUrl: '/wide.jpg', media: { type: 'POSTER', url: '/poster.jpg' } };
const theater = { id: 71, name: '서울 극장', address: '서울', latitude: null, longitude: null };
const show = { id: 91, movieId: 41, theaterId: 71, screenName: '1관', startTime: `${seoulDate()}T23:00:00+09:00`, endTime: `${seoulDate()}T01:00:00+09:00`, endsNextDay: true, availableSeats: 20, totalSeats: 108, maxContiguousSeats: 3, layoutComplete: true };
const member = { id: 1, nickname: '테스터', birthDate: '2000-01-01', address: '서울', preferredTheaters: [1, 2, 3, 4].map(id => ({ theaterId: id, theaterName: `선호${id}`, priority: id })), preferredSeats: [{ position: 'MIDDLE_MIDDLE', priority: 1 }] };
const json = data => new Response(JSON.stringify(data));
async function baseFetch(url) {
    if (url === '/api/main') return json({ nowShowing: [movie, { ...movie, id: 42, title: '두 번째 영화' }], comingSoon: [] });
    if (url === '/api/users/me') return json(member);
    if (url === '/api/booking-groups') return new Response(JSON.stringify({message:'테스트 선점 실패',code:'SOLD_OUT'}), {status:409});
    if (url === '/api/auth/login') return json({ accessToken: 'test-token' });
    if (url.startsWith('/api/movies?')) return json({ items: [movie, { ...movie, id: 42, title: '두 번째 영화' }], page: 0, size: 20, totalElements: 2 });
    if (url === '/api/movies/41') return json(movie);
    if (url === '/api/movies/42') return json({ ...movie, id: 42, title: '두 번째 영화' });
    if (url.startsWith('/api/theaters?')) return json({ items: [theater], page: 0, size: 20, totalElements: 1 });
    if (url === '/api/theaters/71') return json(theater);
    if (url.startsWith('/api/theaters/71/movies?')) return json({ items: [{ ...movie, movieId: 41 }] });
    if (url.startsWith('/api/showtimes/availability?')) return json({ available: true, latestStartTime: show.startTime });
    if (url.startsWith('/api/showtimes?')) return json({ items: [show], serverTime: `${seoulDate()}T00:00:00+09:00` });
    throw new Error(`Unexpected request: ${url}`);
}
function Probe() { const navigate = useNavigate(); const location = useLocation(); return <><output data-testid="url">{location.pathname}{location.search}</output><button onClick={() => navigate(-1)}>뒤로</button><button onClick={() => navigate(`/movies?movie=42&party=2&date=${seoulDate()}`)}>다른 영화</button></>; }
function mount(path) { window.history.replaceState({}, '', path); return render(<StrictMode><MemoryRouter initialEntries={[path]}><App /><Probe /></MemoryRouter></StrictMode>); }
const nativeShowModal = HTMLDialogElement.prototype.showModal;
beforeEach(() => { HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); }; localStorage.clear(); sessionStorage.clear(); vi.stubGlobal('fetch', vi.fn(baseFetch)); vi.spyOn(Date, 'now').mockReturnValue(Date.parse(`${seoulDate()}T09:00:00+09:00`)); });
afterEach(() => { cleanup(); if (nativeShowModal) HTMLDialogElement.prototype.showModal = nativeShowModal; else delete HTMLDialogElement.prototype.showModal; vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

test('details and availability load in parallel while audience controls already work', async () => {
    let detailDone, availabilityDone;
    fetch.mockImplementation(url => url === '/api/movies/41' ? new Promise(resolve => { detailDone = resolve; })
        : url.startsWith('/api/showtimes/availability?') ? new Promise(resolve => { availabilityDone = resolve; }) : baseFetch(url));
    mount(`/movies?movie=41&from=22:00&until=02:00&date=${seoulDate()}`);
    await waitFor(() => { expect(detailDone).toBeTruthy(); expect(availabilityDone).toBeTruthy(); });
    fireEvent.click(screen.getByRole('button', { name: '성인 인원 늘리기' }));
    expect(screen.getByLabelText('성인 인원').textContent).toBe('2');
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    await act(async () => detailDone(json(movie)));
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    await act(async () => availabilityDone(json({ available: true, latestStartTime: show.startTime })));
    await waitFor(() => expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false));
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes?'))).toBe(false);
});

test.each([false, true])('availability scopes preferred theaters only for members: %s', async loggedIn => {
    if (loggedIn) localStorage.setItem('accessToken', 'saved-token');
    mount('/movies?movie=41');
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes/availability?'))).toBe(true));
    const calls = fetch.mock.calls.filter(([url]) => url.startsWith('/api/showtimes/availability?'));
    for (const [url] of calls) expect(new URL(url, 'http://localhost').searchParams.get('theaterIds')).toBe(loggedIn ? '1,2,3,4' : null);
});
test('movie tab stays on home and direct catalog navigation never selects a default film', async () => {
    mount('/');
    await screen.findByRole('link', { name: '서울의 밤 이미지로 예매하기' });
    fireEvent.click(screen.getByText('영화', { exact: true }));
    expect(screen.getByTestId('url').textContent).toBe('/');
    cleanup();
    mount('/movies?party=3&from=10%3A00&until=22%3A00');
    await screen.findByRole('heading', { name: '영화 목록' });
    expect(new URL(screen.getByTestId('url').textContent, 'http://localhost').searchParams.has('movie')).toBe(false);
});
test('party uses counters; elapsed showtimes and restored past ranges cannot enter booking', async () => {
    const clock = vi.spyOn(Date, 'now').mockReturnValue(Date.parse(`${seoulDate()}T22:59:00+09:00`));
    try {
        mount(`/movies?movie=41&party=2&from=23:00&until=02:00&date=${seoulDate()}`);
        await screen.findByLabelText('성인 인원');
        expect(screen.queryByRole('combobox')).toBe(null);
        const enter = screen.getByRole('button', { name: '스마트예매' });
        await waitFor(() => expect(enter.disabled).toBe(false));
        clock.mockReturnValue(Date.parse(`${seoulDate()}T23:00:01+09:00`));
        await waitFor(() => expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true), { timeout: 2500 });
        expect(screen.getByText('선택한 날짜에 예매 가능한 상영회차가 없습니다.')).toBeTruthy();
    } finally { clock.mockRestore(); }
});

test('home artwork and movie cards link to the actual selected movie ID', async () => {
    mount('/');
    const hero = await screen.findByRole('link', { name: '서울의 밤 이미지로 예매하기' });
    expect(hero.getAttribute('href')).toBe('/movies?movie=41');
    fireEvent.click(screen.getByRole('link', { name: /두 번째 영화 포스터/ }));
    await screen.findByRole('heading', { name: '두 번째 영화' });
    expect(screen.getByTestId('url').textContent).toBe('/movies?movie=42');
});
test('home excludes movies without landscape artwork and requests filtered pagination', async () => {
    fetch.mockImplementation(url => url.startsWith('/api/movies?')
        ? Promise.resolve(json({ items: [movie, { ...movie, id: 42, title: '세로만 있는 영화', backdropUrl: null }], size: 20, totalElements: 1 })) : baseFetch(url));
    mount('/');
    await screen.findByRole('link', { name: '서울의 밤', exact: true });
    expect(screen.queryByText('세로만 있는 영화')).toBe(null);
    expect(fetch.mock.calls.some(([url]) => url.includes('landscapeOnly=true'))).toBe(true);
});

test('home uses title artwork while booking always uses a text title', async () => {
    fetch.mockImplementation(url => url.startsWith('/api/movies?')
        ? Promise.resolve(json({ items: [{ ...movie, logoUrl: '/logo.png' }], size: 20, totalElements: 1 }))
        : url === '/api/movies/41' ? Promise.resolve(json({ ...movie, logoUrl: '/logo.png' })) : baseFetch(url));
    mount('/');
    const logo = await screen.findByAltText('서울의 밤');
    expect(logo.getAttribute('src')).toBe('/logo.png');
    const book = screen.getByRole('link', { name: '예매하기', exact: true });
    expect(book.getAttribute('href')).toBe('/movies?movie=41');
    expect(book.querySelector('svg')).not.toBe(null);
    expect(logo.closest('section').querySelector('p')).toBe(null);
    fireEvent.click(screen.getByRole('link', { name: '서울의 밤', exact: true }));
    expect((await screen.findByRole('heading', { name: '서울의 밤' })).textContent).toBe('서울의 밤');
    expect(screen.queryByAltText('서울의 밤')).toBe(null);
    expect(screen.queryByRole('link', { name: '홈으로' })).toBe(null);
    fireEvent.click(screen.getByRole('link', { name: 'SmartTicketing' }));
    fireEvent.error(await screen.findByAltText('서울의 밤'));
    expect(screen.getByRole('heading', { name: '서울의 밤' }).textContent).toBe('서울의 밤');
});

test('a failed landscape never falls back to an enlarged portrait', async () => {
    fetch.mockImplementation(url => url === '/api/movies/41' ? Promise.resolve(json({ ...movie, backdropUrl: '/wide.jpg' })) : baseFetch(url));
    mount('/movies?movie=41');
    const backdrop = await screen.findByAltText('서울의 밤 배경');
    expect(backdrop.getAttribute('src')).toBe('/wide.jpg');
    expect(screen.queryByAltText('서울의 밤 포스터')).toBe(null);
    fireEvent.error(backdrop);
    expect(screen.queryByAltText('서울의 밤 포스터')).toBe(null);
    await screen.findByText('등록된 이미지가 없습니다.');
});

test('trailer iframe opens only on request and is removed when closed', async () => {
    const original = HTMLDialogElement.prototype.showModal;
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
    try {
        fetch.mockImplementation(url => url === '/api/movies/41' ? Promise.resolve(json({ ...movie, media: { type: 'TRAILER', url: 'https://www.youtube.com/watch?v=abcdefghijk' } })) : baseFetch(url));
        mount('/movies?movie=41');
        const play = await screen.findByRole('button', { name: '서울의 밤 예고편 재생' });
        expect(document.querySelector('iframe')).toBe(null);
        fireEvent.click(play);
        expect(screen.getByTitle('서울의 밤 예고편').getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/abcdefghijk');
        fireEvent.click(screen.getByRole('button', { name: '예고편 닫기' }));
        expect(document.querySelector('iframe')).toBe(null);
    } finally {
        if (original) HTMLDialogElement.prototype.showModal = original;
        else delete HTMLDialogElement.prototype.showModal;
    }
});
test('movie restores audience and midnight bounds without showing inventory notes', async () => {
    mount(`/movies?movie=41&date=${seoulDate()}&party=2&from=22:00&until=02:00`);
    await waitFor(() => expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false));
    expect(screen.queryByText('조회상 선택 가능')).toBe(null);
    expect(screen.getByLabelText('성인 인원').textContent).toBe('2');
    expect(screen.getByAltText('서울의 밤 배경')).toBeTruthy();
    expect(screen.queryByText('23:00 → 01:00')).toBe(null);
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes?'))).toBe(false);
    
    fireEvent.click(screen.getByRole('button', { name: '스마트예매' }));
    await screen.findByRole('heading', { name: '스마트예매' });
    expect(await screen.findByText(/로그인하고 스마트예매를 시작하세요/)).toBeTruthy();
    expect(fetch.mock.calls.every(([, options]) => options.method === 'GET')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '뒤로' }));
    expect((await screen.findByLabelText('성인 인원')).textContent).toBe('2');
    for (let i = 0; i < 4; i++) fireEvent.click(screen.getByRole('button', { name: '청소년 인원 늘리기' }));
    expect(screen.getByLabelText('청소년 인원').textContent).toBe('4');
    expect(screen.getByLabelText('총인원').textContent).toBe('6명');
    expect(screen.getByRole('button', { name: '성인 인원 늘리기' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: '청소년 인원 늘리기' }).disabled).toBe(true);
});

test('lightweight availability is independent of audience and time selections', async () => {
    mount('/movies?movie=41');
    await screen.findByRole('heading', { name: '서울의 밤' });
    await screen.findByLabelText('성인 인원');
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes') && !url.includes('startFrom='))).toBe(true);
    expect(fetch.mock.calls.some(([url]) => url.includes('startFrom='))).toBe(false);
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '영화 최소 시작시간 선택' }));
    fireEvent.change(screen.getByRole('combobox', { name: '최소 시작시간 시' }), { target: { value: '10' } });
    expect(fetch.mock.calls.some(([url]) => url.includes('startFrom='))).toBe(false);
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    fireEvent.change(screen.getByRole('combobox', { name: '최대 시작시간 시' }), { target: { value: '12' } });
    expect(fetch.mock.calls.some(([url]) => url.includes('startFrom='))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '이 시간으로 적용' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false));
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes?'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '영화 최소 시작시간 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '다시 선택' }));
    expect(screen.getByRole('button', { name: '이 시간으로 적용' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '시간 선택 닫기' }));
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false);
});
test('late showtime response cannot overwrite a newer movie selection', async () => {
    let resolveOld;
    fetch.mockImplementation(url => url.startsWith('/api/showtimes/availability?movieId=41') ? new Promise(resolve => { resolveOld = resolve; }) : baseFetch(url));
    mount(`/movies?movie=41&party=2&from=22:00&until=02:00&date=${seoulDate()}`);
    await waitFor(() => expect(resolveOld).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '다른 영화' }));
    await screen.findByRole('heading', { name: '두 번째 영화' });
    await act(async () => resolveOld(json({ available: false, latestStartTime: null })));
    expect(screen.queryByText('선택한 날짜에 예매 가능한 상영회차가 없습니다.')).toBe(null);
});
test('brand tabs filter branches and clear previous booking selections', async () => {
    fetch.mockImplementation(url => {
        if (url === '/api/theaters/71') return Promise.resolve(json({ ...theater, brand: 'LOTTE_CINEMA' }));
        if (url.startsWith('/api/theaters?')) {
            const brand = new URL(url, 'http://localhost').searchParams.get('brand');
            return Promise.resolve(json({ items: [{ ...theater, name: `${brand} 지점` }], page: 0, size: 20, totalElements: 1 }));
        }
        return baseFetch(url);
    });
    mount(`/theaters?theater=71&movie=41&showtime=91&q=old&page=2&date=${seoulDate()}`);
    await waitFor(() => expect(screen.getByRole('tab', { name: '롯데시네마' }).getAttribute('aria-selected')).toBe('true'));
    fireEvent.click(screen.getByRole('tab', { name: '메가박스' }));
    await screen.findByRole('button', { name: /MEGABOX 지점/ });
    const params = new URL(screen.getByTestId('url').textContent, 'http://localhost').searchParams;
    expect(params.get('brand')).toBe('MEGABOX');
    expect(params.has('page')).toBe(false);
    for (const key of ['theater', 'movie', 'showtime', 'q']) expect(params.has(key)).toBe(false);
    expect(screen.queryByRole('button', { name: /LOTTE_CINEMA 지점/ })).toBe(null);
});

test('a directly opened theater retains its brand without a duplicate theater summary', async () => {
    fetch.mockImplementation(url => url === '/api/theaters/71'
        ? Promise.resolve(json({ ...theater, brand: 'MEGABOX' })) : baseFetch(url));
    mount('/theaters?theater=71');
    await waitFor(() => expect(screen.getByRole('tab', { name: '메가박스' }).getAttribute('aria-selected')).toBe('true'));
    expect(screen.queryByRole('button', { name: '극장 변경' })).toBe(null);
    expect(screen.queryByText(theater.address)).toBe(null);
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/theaters?') && url.includes('brand=MEGABOX'))).toBe(true));
});

test('theater entry restores IDs and row panel with audience selection', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText((_, element) => element.tagName === 'STRONG' && element.textContent === '23:00 → 01:00');
    expect(screen.getByLabelText('총인원').textContent).toBe('1명');
    expect(screen.getByRole('region', { name: '상영 회차' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '일반예매' }));
    await screen.findByRole('heading', { name: '일반 예매' });
    expect(screen.getByTestId('url').textContent).toContain('entry=THEATER_NORMAL');
    expect(await screen.findByText(/로그인 후 좌석을 선택할 수 있습니다/)).toBeTruthy();
});
test('a showtime opens booking choices and closing restores the showtime card', async () => {
    mount(`/theaters?theater=71&date=${seoulDate()}`);
    const card = await screen.findByRole('button', { name: /20 \/ 108석/ });
    card.focus();
    fireEvent.click(card);
    const dialog = await screen.findByRole('dialog', { name: '서울의 밤' });
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(screen.queryByText('선택')).toBe(null);
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.click(screen.getByRole('button', { name: '예매 방식 선택 닫기' }));
    expect(screen.queryByRole('dialog', { name: '서울의 밤' })).toBe(null);
    expect(document.activeElement).toBe(card);
    expect(document.body.style.overflow).not.toBe('hidden');
    fireEvent.click(card);
    fireEvent(await screen.findByRole('dialog', { name: '서울의 밤' }), new Event('cancel', { bubbles: true, cancelable: true }));
    expect(screen.queryByRole('dialog', { name: '서울의 밤' })).toBe(null);
});

test('sold out and unknown layouts have distinct button behavior', async () => {
    fetch.mockImplementation(url => url.startsWith('/api/showtimes?') ? Promise.resolve(json({ items: [{ ...show, layoutComplete: false }, { ...show, id: 92, availableSeats: 0 }] })) : baseFetch(url));
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByRole('button', { name: /20 \/ 108석 배치 미확인/ });
    expect(screen.getByRole('button', { name: /0 \/ 108석 매진/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false);
    expect(screen.getByRole('button', { name: '일반예매' }).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: /0 \/ 108석 매진/ }));
    expect(screen.getByRole('button', { name: '일반예매' }).disabled).toBe(true);
});
test('loading, error, retry and empty states stay distinct', async () => {
    let finish;
    fetch.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    mount('/movies'); expect(screen.queryByText('불러오는 중…')).toBeNull();
    await waitFor(() => expect(typeof finish).toBe('function'));
    await act(async () => finish(new Response(JSON.stringify({ message: '일시적 오류' }), { status: 503 })));
    await screen.findByRole('alert');
    fetch.mockResolvedValue(json({ items: [], size: 20, totalElements: 0 }));
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('조건에 맞는 결과가 없습니다.');
    expect(screen.queryByRole('alert')).toBe(null);
});
test('map opens in a dismissible dialog and guests can use brand tabs without search', async () => {
    const locate = vi.fn((success, failure) => failure({ code: 1 }));
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: locate } });
    vi.stubEnv('VITE_KAKAO_MAP_JS_KEY', '');
    mount('/theaters');
    expect((await screen.findByRole('tab', { name: '선호극장' })).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByLabelText('극장 이름 또는 주소 검색')).toBe(null);
    expect(locate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '지도에서 선택' }));
    expect(await screen.findByRole('dialog', { name: '극장 지도' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '현재 위치로 이동' })).toBeTruthy();
    expect(locate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '지도 닫기' }));
    expect(screen.queryByRole('dialog')).toBe(null);
    expect(document.body.style.overflow).not.toBe('hidden');
    fireEvent.click(await screen.findByRole('tab', { name: 'CGV' }));
    await screen.findByRole('button', { name: /서울 극장/ });
    expect(fetch.mock.calls.some(([url]) => url.includes('/nearby'))).toBe(false);
});

test('all preferred theaters are visible in the default first tab', async () => {
    localStorage.setItem('accessToken', 'saved-token'); mount('/theaters');
    await screen.findByRole('button', { name: '선호1', exact: true });
    expect(screen.getByRole('button', { name: '선호4', exact: true })).toBeTruthy();
    expect(screen.getAllByRole('tab').map(tab => tab.textContent)).toEqual(['선호극장', 'CGV', '롯데시네마', '메가박스']);
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/theaters?'))).toBe(false);
});

test('login and reload resume the requested smart booking', async () => {
    const path = `/movies?movie=41&party=2&from=22:00&until=02:00&date=${seoulDate()}&entry=MOVIE_SMART`;
    mount(path);
    fireEvent.click(await screen.findByRole('link', { name: /로그인하고 이 선택으로 돌아오기/ }));
    fireEvent.change(await screen.findByLabelText('아이디'), { target: { value: 'test' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인', exact: true }));
    await screen.findByRole('heading', { name: '스마트예매' });
    expect(decodeURIComponent(screen.getByTestId('url').textContent)).toBe(path);
    cleanup(); mount(path);
    await screen.findByText(/테스트 선점 실패/);
    expect(fetch.mock.calls.some(([url]) => url === '/api/booking-groups')).toBe(true);
});
test('expired date cannot enter the future flow', async () => {
    localStorage.setItem('accessToken', 'saved-token');
    mount('/movies?movie=41&party=2&date=2020-01-01&entry=MOVIE_SMART');
    await screen.findByText(/인원 또는 상영 조건을 확인해주세요/);
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes'))).toBe(false);
});

test('late movie detail cannot replace the new detail and its query signal is aborted', async () => {
    let finish, oldSignal;
    fetch.mockImplementation((url, options) => {
        if (url === '/api/movies/41') { oldSignal = options.signal; return new Promise(resolve => { finish = resolve; }); }
        return baseFetch(url);
    });
    mount('/movies?movie=41');
    await waitFor(() => expect(finish).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '다른 영화' }));
    await screen.findByRole('heading', { name: '두 번째 영화' });
    expect(oldSignal.aborted).toBe(true);
    await act(async () => finish(json({ ...movie, title: '오래된 상세' })));
    expect(screen.queryByRole('heading', { name: '오래된 상세' })).toBe(null);
});

test('changing brands ignores an older branch response even when cancellation is ignored', async () => {
    let finishOld;
    fetch.mockImplementation(url => {
        if (url.startsWith('/api/theaters?') && url.includes('brand=CGV')) return new Promise(resolve => { finishOld = resolve; });
        if (url.startsWith('/api/theaters?') && url.includes('brand=MEGABOX')) return Promise.resolve(json({ items: [{ ...theater, name: '새 브랜드 극장' }], totalElements: 1 }));
        return baseFetch(url);
    });
    mount('/theaters');
    fireEvent.click(await screen.findByRole('tab', { name: 'CGV' }));
    await waitFor(() => expect(finishOld).toBeTruthy());
    fireEvent.click(screen.getByRole('tab', { name: '메가박스' }));
    await screen.findByRole('button', { name: '새 브랜드 극장' });
    await act(async () => finishOld(json({ items: [{ ...theater, name: '이전 브랜드 극장' }], totalElements: 1 })));
    expect(screen.queryByRole('button', { name: '이전 브랜드 극장' })).toBe(null);
});

test('brand tabs fetch every catalog page without paging controls', async () => {
    fetch.mockImplementation(url => {
        if (!url.startsWith('/api/theaters?')) return baseFetch(url);
        const page = Number(new URL(url, 'http://localhost').searchParams.get('page'));
        return Promise.resolve(json({ items: page === 0 ? Array.from({ length: 100 }, (_, id) => ({ ...theater, id: id + 1, name: `지점${id + 1}` })) : [{ ...theater, id: 101, name: '마지막 지점' }], totalElements: 101 }));
    });
    mount('/theaters');
    fireEvent.click(await screen.findByRole('tab', { name: 'CGV' }));
    await screen.findByRole('button', { name: '마지막 지점' });
    expect(screen.queryByRole('button', { name: '다음 페이지' })).toBe(null);
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/theaters?') && url.includes('page=1'))).toBe(true);
});

test('date change clears movie and showtime, preserves the theater and seven dates', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText((_, element) => element.tagName === 'STRONG' && element.textContent === '23:00 → 01:00');
    const buttons = screen.getAllByRole('button').filter(b => /^\d{4}-\d{2}-\d{2}$/.test(b.getAttribute('aria-label') || ''));
    expect(buttons).toHaveLength(7);
    fireEvent.click(buttons[1]);
    const url = new URL(screen.getByTestId('url').textContent, 'http://localhost');
    expect(url.searchParams.get('theater')).toBe('71');
    expect(url.searchParams.has('movie')).toBe(false);
    expect(url.searchParams.has('showtime')).toBe(false);
    expect(screen.getByRole('region', { name: '상영 회차' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /23:00 → 01:00/, pressed: true })).toBe(null);
    expect(screen.queryByRole('button', { name: '일반예매' })).toBe(null);
});

test('OAuth callback restores the same saved booking and strips token from address', async () => {
    const path = `/movies?movie=41&party=2&from=22%3A00&until=02%3A00&date=${seoulDate()}&entry=MOVIE_SMART`;
    sessionStorage.setItem('booking.return', path);
    mount('/oauth2/callback#token=oauth-test-token');
    await screen.findByText(/테스트 선점 실패/);
    expect(screen.getByTestId('url').textContent).toBe(path);
    expect(fetch.mock.calls.find(([url]) => url === '/api/users/me')[1].headers.Authorization).toBe('Bearer oauth-test-token');
});

test('guest manual entry requires login and never promises newly sold-out inventory', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText((_, element) => element.tagName === 'STRONG' && element.textContent === '23:00 → 01:00');
    fetch.mockImplementation(url => url.startsWith('/api/showtimes?') ? Promise.resolve(json({ items: [{ ...show, availableSeats: 0 }] })) : baseFetch(url));
    fireEvent.click(screen.getByRole('button', { name: '일반예매' }));
    await screen.findByText(/로그인 후 좌석을 선택할 수 있습니다/);
    expect(screen.queryByText(/실제 결제와 자동 대기 등록은 진행되지 않습니다/)).toBe(null);
});

test('administrator without preference information can open theater pages directly', async () => {
    localStorage.setItem('accessToken', 'admin-test-token');
    fetch.mockImplementation(url => url === '/api/users/me'
        ? Promise.resolve(json({ id: 1, nickname: '관리자', admin: true, preferredTheaters: [], preferredSeats: [] }))
        : baseFetch(url));
    mount('/theaters');
    await screen.findByText(/등록된 선호극장이 없습니다/);
    expect(screen.getByTestId('url').textContent).toBe('/theaters');
    fireEvent.click(await screen.findByRole('tab', { name: 'CGV' }));
    await screen.findByRole('button', { name: /서울 극장/ });
    expect(screen.queryByRole('heading', { name: '선호 정보 설정' })).toBe(null);
});

test('incomplete member deep link survives required preference setup', async () => {
    localStorage.setItem('accessToken', 'setup-test-token');
    fetch.mockImplementation(url => url === '/api/users/me' ? Promise.resolve(json({ ...member, preferredSeats: [] })) : baseFetch(url));
    const path = `/movies?movie=41&party=2&from=22%3A00&until=02%3A00&date=${seoulDate()}&entry=MOVIE_SMART`;
    mount(path);
    await screen.findByRole('heading', { name: '선호 정보 설정' });
    expect(sessionStorage.getItem('booking.return')).toBe(path);
    fireEvent.click(screen.getByRole('button', { name: '선호 설정 완료' }));
    await screen.findByText(/테스트 선점 실패/);
    expect(screen.getByTestId('url').textContent).toBe(path);
});

function stubLocationMap() {
    const initialCenters = [];
    const setCenter = vi.fn();
    const markers = [];
    const overlays = [];
    const listeners = new Map();
    const marker = vi.fn(function (options) {
        this.options = options;
        this.setMap = vi.fn();
        this.setImage = vi.fn();
        markers.push(this);
    });
    const locate = vi.fn();
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: locate } });
    vi.stubGlobal('kakao', { maps: {
        load: callback => callback(),
        LatLng: class { constructor(lat, lng) { this.lat = lat; this.lng = lng; } },
        Map: class {
            constructor(container, options) { initialCenters.push(options.center); this.setCenter = setCenter; }
            getProjection() { return { containerPointFromCoords: () => ({ y: 100 }) }; }
        },
        CustomOverlay: class {
            constructor(options) { this.options = options; this.setMap = vi.fn(); this.setPosition = vi.fn(); overlays.push(this); }
            getContent() { return this.options.content; }
        },
        Marker: marker,
        event: {
            addListener: (target, event, handler) => { if (!listeners.has(target)) listeners.set(target, new Map()); listeners.get(target).set(event, handler); },
            removeListener: (target, event) => { const events = listeners.get(target); events?.delete(event); if (!events?.size) listeners.delete(target); },
        },
    } });
    return { initialCenters, setCenter, marker, locate, markers, listeners, overlays };
}

test.each([false, true])('map starts in Seoul and only moves on location button click, member: %s', async loggedIn => {
    if (loggedIn) localStorage.setItem('accessToken', 'map-test-token');
    const { initialCenters, setCenter, marker, locate } = stubLocationMap();
    mount('/theaters?map=1');
    const button = await screen.findByRole('button', { name: '현재 위치로 이동' });
    await waitFor(() => expect(button.disabled).toBe(false));
    expect(initialCenters.length).toBeGreaterThan(0);
    expect(initialCenters.every(center => center.lat === 37.5665 && center.lng === 126.978)).toBe(true);
    expect(locate).not.toHaveBeenCalled();
    expect(marker).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '이 지도 위치 주변 검색' })).toBe(null);
    expect(screen.getByRole('dialog').querySelectorAll('button')).toHaveLength(2);
    fireEvent.click(button);
    expect(locate).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '현재 위치 확인 중…' }).disabled).toBe(true);
    act(() => locate.mock.calls[0][0]({ coords: { latitude: 35.1796, longitude: 129.0756 } }));
    expect(setCenter).toHaveBeenCalledWith(expect.objectContaining({ lat: 35.1796, lng: 129.0756 }));
    expect(screen.getByRole('button', { name: '현재 위치로 이동' }).disabled).toBe(false);
    expect(screen.getByTestId('url').textContent).toBe('/theaters?map=1');
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/theaters/nearby'))).toBe(false);
});

test.each([false, true])('theater pins remain selectable without nearby search, member: %s', async loggedIn => {
    if (loggedIn) localStorage.setItem('accessToken', 'map-test-token');
    const { markers, listeners, locate, overlays } = stubLocationMap();
    fetch.mockImplementation(url => url.startsWith('/api/theaters?')
        ? Promise.resolve(json({ items: [{ ...theater, brand: 'CGV', latitude: 37.5665, longitude: 126.978 }], totalElements: 1 }))
        : baseFetch(url));
    mount('/theaters?map=1');
    await screen.findByRole('dialog', { name: '극장 지도' });
    await waitFor(() => expect(listeners.size).toBe(1));
    const pin = [...listeners.keys()][0];
    await waitFor(() => expect(pin.setImage).toHaveBeenCalledWith({ brand: 'CGV' }));
    act(() => listeners.get(pin).get('mouseover')());
    const tooltip = overlays.find(overlay => overlay.getContent().textContent === '서울 극장');
    expect(tooltip.setMap).toHaveBeenLastCalledWith(pin.options.map);
    const callsWhileVisible = tooltip.setMap.mock.calls.length;
    act(() => listeners.get(pin).get('mouseover')());
    expect(tooltip.setMap.mock.calls).toHaveLength(callsWhileVisible);
    act(() => listeners.get(pin).get('mouseout')());
    expect(tooltip.setMap).toHaveBeenLastCalledWith(null);
    expect(pin.options.position).toEqual(expect.objectContaining({ lat: 37.5665, lng: 126.978 }));
    expect(locate).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '이 지도 위치 주변 검색' })).toBe(null);
    act(() => listeners.get(pin).get('click')());
    await waitFor(() => expect(screen.getByTestId('url').textContent).toContain('theater=71'));
    expect(screen.queryByRole('dialog')).toBe(null);
    expect(markers.every(marker => marker.setMap.mock.calls.some(([map]) => map === null))).toBe(true);
    expect(fetch.mock.calls.some(([url]) => url.includes('/nearby'))).toBe(false);
});

test('leaving the previous marker cannot dismiss the current theater tooltip', async () => {
    const { listeners, overlays } = stubLocationMap();
    fetch.mockImplementation(url => url.startsWith('/api/theaters?')
        ? Promise.resolve(json({ items: [
            { ...theater, brand: 'CGV', latitude: 37.5665, longitude: 126.978 },
            { ...theater, id: 72, name: '두 번째 극장', brand: 'MEGABOX', latitude: 37.567, longitude: 126.979 },
        ], totalElements: 2 })) : baseFetch(url));
    mount('/theaters?map=1');
    await waitFor(() => expect(listeners.size).toBe(2));
    const [first, second] = [...listeners.keys()];
    act(() => listeners.get(first).get('mouseover')());
    act(() => listeners.get(second).get('mouseover')());
    const tooltip = overlays.find(overlay => overlay.getContent().textContent === '두 번째 극장');
    act(() => listeners.get(first).get('mouseout')());
    expect(tooltip.setMap).toHaveBeenLastCalledWith(second.options.map);
    act(() => listeners.get(second).get('mouseout')());
    expect(tooltip.setMap).toHaveBeenLastCalledWith(null);
    cleanup();
    expect(listeners.size).toBe(0);
});

test('location denial keeps the map in place and permits retry; a closed map ignores late results', async () => {
    const { setCenter, locate } = stubLocationMap();
    mount('/theaters?map=1');
    const button = await screen.findByRole('button', { name: '현재 위치로 이동' });
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
    act(() => locate.mock.calls[0][1]({ code: 1 }));
    expect(screen.getByText(/위치 권한이 허용되지 않았습니다/)).toBeTruthy();
    expect(setCenter).not.toHaveBeenCalled();
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    fireEvent.click(screen.getByRole('button', { name: '지도 닫기' }));
    act(() => locate.mock.calls[1][0]({ coords: { latitude: 35.1796, longitude: 129.0756 } }));
    expect(setCenter).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBe(null);
});

test('movie details retain main metadata with spaced fields instead of middle dots', async () => {
    fetch.mockImplementation(url => url === '/api/movies/41'
        ? Promise.resolve(json({ ...movie, rating: '12', releaseDate: '2026-10-01', genres: '액션, 모험', director: '감독 이름', castNames: '배우 이름' }))
        : baseFetch(url));
    mount('/movies?movie=41');
    await screen.findByText('2026.10.01');
    expect(screen.getByText('개봉일')).toBeTruthy();
    expect(screen.getByText('12세')).toBeTruthy();
    expect(screen.getByText('액션 / 모험')).toBeTruthy();
    expect([...screen.getByText('개봉일').parentElement.querySelectorAll('dt')].map(label => label.textContent)).toEqual(['개봉일', '감독', '출연']);
    expect(screen.getByText('감독 이름')).toBeTruthy();
    expect(screen.getByText('배우 이름')).toBeTruthy();
    expect(screen.getByRole('region', { name: '서울의 밤 영화 소개' }).textContent).not.toContain('·');
});

 test('theater movies follow main popularity while retaining brand selection', async () => {
    fetch.mockImplementation(url => url.startsWith('/api/theaters/71/movies?')
        ? Promise.resolve(json({ items: [{ ...movie, movieId: 42, title: '두 번째 영화' }, { ...movie, movieId: 41 }] })) : baseFetch(url));
    mount('/theaters?theater=71');
    await screen.findByRole('region', { name: '두 번째 영화 상영 시간' });
    await waitFor(() => {
        const rows = screen.getByRole('region', { name: '상영 회차' }).querySelectorAll('li');
        expect(rows[0].textContent).toContain('서울의 밤');
        expect(rows[1].textContent).toContain('두 번째 영화');
    });
    expect(screen.getByRole('tab', { name: 'CGV' })).toBeTruthy();
 });

test('changing movie dates replaces history so back returns to the previous page', async () => {
    mount('/');
    fireEvent.click(await screen.findByRole('link', { name: '서울의 밤 이미지로 예매하기' }));
    await screen.findByLabelText('성인 인원');
    const days = dates(seoulDate());
    fireEvent.click(screen.getByRole('button', { name: days[1] }));
    fireEvent.click(screen.getByRole('button', { name: days[2] }));
    expect(screen.getByTestId('url').textContent).toContain(`date=${days[2]}`);
    fireEvent.click(screen.getByRole('button', { name: '뒤로' }));
    await waitFor(() => expect(screen.getByTestId('url').textContent).toBe('/'));
});

test('empty day keeps booking controls and explains release context', async () => {
    const days = dates(seoulDate());
    fetch.mockImplementation(url => url === '/api/movies/41'
        ? Promise.resolve(json({ ...movie, releaseDate: days[2] }))
        : url.startsWith('/api/showtimes/availability?') ? Promise.resolve(json({ available: false, latestStartTime: null })) : baseFetch(url));
    mount('/movies?movie=41');
    await screen.findByText('선택한 날짜에 예매 가능한 상영회차가 없습니다.');
    expect(screen.getByText(new RegExp(`${days[2].replaceAll('-', '\\.')} 개봉 예정입니다`))).toBeTruthy();
    expect(screen.getByLabelText('성인 인원')).toBeTruthy();
    expect(screen.getByRole('button', { name: '영화 최소 시작시간 선택' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: days[2] })).toBeTruthy();
});

test('date changes keep controls but discard stale availability', async () => {
    const days = dates(seoulDate());
    let finish;
    fetch.mockImplementation(url => url.startsWith('/api/showtimes/availability?') && url.includes(`date=${days[1]}`)
        ? new Promise(resolve => { finish = resolve; }) : baseFetch(url));
    mount('/movies?movie=41');
    await screen.findByLabelText('성인 인원');
    fireEvent.click(screen.getByRole('button', { name: days[1] }));
    expect(screen.getByLabelText('성인 인원')).toBeTruthy();
    expect(screen.queryByText('상영회차를 확인하고 있습니다. 인원과 시간은 먼저 선택할 수 있습니다.')).toBeNull();
    await waitFor(() => expect(finish).toBeTruthy());
    await act(async () => finish(json({ available: false, latestStartTime: null })));
    await screen.findByText('선택한 날짜에 예매 가능한 상영회차가 없습니다.');
    fireEvent.click(screen.getByRole('button', { name: days[0] }));
    await screen.findByLabelText('성인 인원');
});

test('schedule errors are retryable and are not presented as an empty schedule', async () => {
    fetch.mockImplementation(url => url.startsWith('/api/showtimes/availability?')
        ? Promise.reject(new Error('상영시간표 조회 실패')) : baseFetch(url));
    mount('/movies?movie=41');
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    expect(screen.queryByText('선택한 날짜에 예매 가능한 상영회차가 없습니다.')).toBe(null);
    expect(screen.getByLabelText('성인 인원')).toBeTruthy();
    fetch.mockImplementation(baseFetch);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByLabelText('성인 인원');
});

test.each(['/movies?movie=41', '/theaters?theater=71&movie=41&showtime=91'])('youth default and adult restriction apply at %s', async path => {
    localStorage.setItem('accessToken', 'youth-test');
    fetch.mockImplementation(url => url === '/api/users/me' ? Promise.resolve(json({ ...member, birthDate:'2010-01-01' })) : baseFetch(url));
    mount(path);
    await waitFor(() => expect(screen.getByLabelText('청소년 인원').textContent).toBe('1'));
    expect(screen.getByLabelText('성인 인원').textContent).toBe('0');
    expect(screen.getByRole('button', {name:'성인 인원 늘리기'}).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', {name:'청소년 인원 늘리기'}));
    expect(screen.getByLabelText('총인원').textContent).toBe('2명');
    expect(screen.queryByRole('checkbox', {name:/관람등급/})).toBeNull();
});


