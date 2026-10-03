import { StrictMode } from 'react';
import { MemoryRouter, useNavigate, useLocation } from 'react-router-dom';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from '../src/App.jsx';
import { seoulDate } from '../src/booking/state.js';
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
    if (url === '/api/auth/login') return json({ accessToken: 'test-token' });
    if (url.startsWith('/api/movies?')) return json({ items: [movie, { ...movie, id: 42, title: '두 번째 영화' }], page: 0, size: 20, totalElements: 2 });
    if (url === '/api/movies/41') return json(movie);
    if (url === '/api/movies/42') return json({ ...movie, id: 42, title: '두 번째 영화' });
    if (url.startsWith('/api/theaters?')) return json({ items: [theater], page: 0, size: 20, totalElements: 1 });
    if (url === '/api/theaters/71') return json(theater);
    if (url.startsWith('/api/theaters/71/movies?')) return json({ items: [{ ...movie, movieId: 41 }] });
    if (url.startsWith('/api/showtimes?')) return json({ items: [show], serverTime: `${seoulDate()}T00:00:00+09:00` });
    throw new Error(`Unexpected request: ${url}`);
}
function Probe() { const navigate = useNavigate(); const location = useLocation(); return <><output data-testid="url">{location.pathname}{location.search}</output><button onClick={() => navigate(-1)}>뒤로</button><button onClick={() => navigate(`/movies?movie=42&party=2&date=${seoulDate()}`)}>다른 영화</button></>; }
function mount(path) { window.history.replaceState({}, '', path); return render(<StrictMode><MemoryRouter initialEntries={[path]}><App /><Probe /></MemoryRouter></StrictMode>); }
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); vi.stubGlobal('fetch', vi.fn(baseFetch)); vi.spyOn(Date, 'now').mockReturnValue(Date.parse(`${seoulDate()}T09:00:00+09:00`)); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
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
        await waitFor(() => expect(enter.disabled).toBe(true), { timeout: 2500 });
        expect(screen.getByRole('alert').textContent).toContain('현재 시각 이후');
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
    expect(screen.queryByRole('link', { name: '← 홈으로' })).toBe(null);
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
    expect(fetch.mock.calls.some(([url]) => url.includes('startFrom=22%3A00&startUntil=02%3A00'))).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '스마트예매' }));
    await screen.findByRole('heading', { name: '좋은 자리는, 알아서.' });
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

test('showtimes are never queried until both time bounds are selected', async () => {
    mount('/movies?movie=41');
    await screen.findByRole('heading', { name: '서울의 밤' });
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes'))).toBe(false);
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '10:00', exact: true }));
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes'))).toBe(false);
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '+2시간 12:00' }));
    expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/showtimes'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '이 시간으로 적용' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false));
    expect(fetch.mock.calls.some(([url]) => url.includes('startFrom=10%3A00&startUntil=12%3A00'))).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '다시 선택' }));
    expect(screen.getByRole('button', { name: '이 시간으로 적용' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '시간 선택 닫기' }));
    expect(screen.getByRole('button', { name: '스마트예매' }).disabled).toBe(false);
});
test('late showtime response cannot overwrite a newer movie selection', async () => {
    let resolveOld;
    fetch.mockImplementation(url => url.startsWith('/api/showtimes?movieId=41') ? new Promise(resolve => { resolveOld = resolve; }) : baseFetch(url));
    mount(`/movies?movie=41&party=2&from=22:00&until=02:00&date=${seoulDate()}`);
    await waitFor(() => expect(resolveOld).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '다른 영화' }));
    await screen.findByRole('heading', { name: '두 번째 영화' });
    await act(async () => resolveOld(json({ items: [{ ...show, screenName: '오래된 회차' }] })));
    expect(screen.queryByText(/오래된 회차/)).toBe(null);
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
    expect(params.get('page')).toBe('0');
    for (const key of ['theater', 'movie', 'showtime', 'q']) expect(params.has(key)).toBe(false);
    expect(screen.queryByRole('button', { name: /LOTTE_CINEMA 지점/ })).toBe(null);
});

test('changing a directly opened theater retains its brand', async () => {
    fetch.mockImplementation(url => url === '/api/theaters/71'
        ? Promise.resolve(json({ ...theater, brand: 'MEGABOX' })) : baseFetch(url));
    mount('/theaters?theater=71');
    fireEvent.click(await screen.findByRole('button', { name: '극장 변경' }));
    expect(screen.getByRole('tab', { name: '메가박스' }).getAttribute('aria-selected')).toBe('true');
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/theaters?') && url.includes('brand=MEGABOX'))).toBe(true));
});

test('theater entry restores IDs and row panel without collecting party', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText('23:00 → 01:00');
    expect(screen.queryByLabelText('총인원')).toBe(null);
    expect(screen.getByRole('region', { name: '상영 회차' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '일반예매' }));
    await screen.findByRole('heading', { name: '나의 자리를 선택하세요' });
    expect(screen.getByTestId('url').textContent).toContain('entry=THEATER_NORMAL');
    expect(await screen.findByText(/로그인 후 좌석을 선택할 수 있습니다/)).toBeTruthy();
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
    mount('/movies'); await screen.findByText('불러오는 중…');
    await waitFor(() => expect(typeof finish).toBe('function'));
    await act(async () => finish(new Response(JSON.stringify({ message: '일시적 오류' }), { status: 503 })));
    await screen.findByRole('alert');
    fetch.mockResolvedValue(json({ items: [], size: 20, totalElements: 0 }));
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('조건에 맞는 결과가 없습니다.');
    expect(screen.queryByRole('alert')).toBe(null);
});
test('map entry requests location, denial keeps manual public search', async () => {
    const locate = vi.fn((success, failure) => failure({ code: 1 }));
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: locate } });
    vi.stubEnv('VITE_KAKAO_MAP_JS_KEY', '');
    mount('/theaters'); await screen.findByRole('button', { name: /서울 극장/ });
    expect(locate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '지도 열기' }));
    await screen.findByText(/주변 극장 검색은 로그인이 필요/);
    expect(locate).toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('극장 이름 또는 주소 검색'), { target: { value: '부산' } });
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('query=%EB%B6%80%EC%82%B0'))).toBe(true));
    expect(fetch.mock.calls.some(([url]) => url.includes('/nearby'))).toBe(false);
});
test('first three preferred theaters and remaining preferences are accessible', async () => {
    localStorage.setItem('accessToken', 'saved-token'); mount('/theaters');
    await screen.findByRole('button', { name: '1순위 선호1' });
    expect(screen.queryByRole('button', { name: '4순위 선호4' })).toBe(null);
    fireEvent.click(screen.getByRole('button', { name: '나머지 선호 극장 보기' }));
    expect(screen.getByRole('button', { name: '4순위 선호4' })).toBeTruthy();
});
test('login and reload restore selection without booking mutation', async () => {
    const path = `/movies?movie=41&party=2&from=22:00&until=02:00&date=${seoulDate()}&entry=MOVIE_SMART`;
    mount(path);
    fireEvent.click(await screen.findByRole('link', { name: /로그인하고 이 선택으로 돌아오기/ }));
    fireEvent.change(await screen.findByLabelText('아이디'), { target: { value: 'test' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'password123' } });
    fireEvent.click(screen.getByRole('button', { name: '로그인', exact: true }));
    await screen.findByRole('heading', { name: '좋은 자리는, 알아서.' });
    expect(decodeURIComponent(screen.getByTestId('url').textContent)).toBe(path);
    cleanup(); mount(path);
    await screen.findByText(/실제 결제와 자동 대기 등록은 진행되지 않습니다/);
    expect(fetch.mock.calls.some(([url]) => /booking-groups|hold|payment|waiting/.test(url))).toBe(false);
});
test('expired date cannot enter the future flow', async () => {
    localStorage.setItem('accessToken', 'saved-token');
    mount('/movies?movie=41&party=2&date=2020-01-01&entry=MOVIE_SMART');
    await screen.findByText(/영화, 날짜, 시간 또는 회차를 다시 선택해주세요/);
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

test('quick theater search ignores the older result even if cancellation is ignored', async () => {
    let finishOld;
    fetch.mockImplementation(url => {
        if (url.includes('query=old')) return new Promise(resolve => { finishOld = resolve; });
        if (url.includes('query=new')) return Promise.resolve(json({ items: [{ ...theater, name: '새 검색 극장' }], size: 20, totalElements: 1 }));
        return baseFetch(url);
    });
    mount('/theaters');
    fireEvent.change(await screen.findByLabelText('극장 이름 또는 주소 검색'), { target: { value: 'old' } });
    await waitFor(() => expect(finishOld).toBeTruthy());
    fireEvent.change(screen.getByLabelText('극장 이름 또는 주소 검색'), { target: { value: 'new' } });
    await screen.findByRole('button', { name: /새 검색 극장/ });
    await act(async () => finishOld(json({ items: [{ ...theater, name: '이전 검색 극장' }], size: 20, totalElements: 1 })));
    expect(screen.queryByRole('button', { name: /이전 검색 극장/ })).toBe(null);
});

test('date change clears movie and showtime, preserves the theater and seven dates', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText('23:00 → 01:00');
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
    await screen.findByText(/실제 결제와 자동 대기 등록은 진행되지 않습니다/);
    expect(screen.getByTestId('url').textContent).toBe(path);
    expect(fetch.mock.calls.find(([url]) => url === '/api/users/me')[1].headers.Authorization).toBe('Bearer oauth-test-token');
});

test('guest manual entry requires login and never promises newly sold-out inventory', async () => {
    mount(`/theaters?theater=71&movie=41&showtime=91&date=${seoulDate()}`);
    await screen.findByText('23:00 → 01:00');
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
    await screen.findByRole('button', { name: /서울 극장/ });
    expect(screen.getByTestId('url').textContent).toBe('/theaters');
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
    await screen.findByText(/실제 결제와 자동 대기 등록은 진행되지 않습니다/);
    expect(screen.getByTestId('url').textContent).toBe(path);
});

test('authenticated nearby results use DB theater IDs and preserve the existing endpoint', async () => {
    localStorage.setItem('accessToken', 'nearby-test-token');
    vi.stubGlobal('navigator', { geolocation: { getCurrentPosition: vi.fn((ok, fail) => fail({ code: 1 })) } });
    vi.stubGlobal('kakao', { maps: {
        load: callback => callback(),
        LatLng: class { constructor(lat, lng) { this.lat = lat; this.lng = lng; } },
        Map: class { getCenter() { return { getLat: () => 37.5665, getLng: () => 126.978 }; } },
        Marker: class { setMap() {} },
        event: { addListener: vi.fn(), removeListener: vi.fn() },
    } });
    fetch.mockImplementation(url => url.startsWith('/api/theaters/nearby?') ? Promise.resolve(json([{ theaterId: 72, kakaoPlaceId: 'external-999', name: '주변 극장', latitude: 37.5, longitude: 127 }])) : baseFetch(url));
    mount('/theaters?map=1');
    const nearbyButton = await screen.findByRole('button', { name: '이 지도 위치 주변 검색' });
    await waitFor(() => expect(nearbyButton.disabled).toBe(false));
    fireEvent.click(nearbyButton);
    fireEvent.click(await screen.findByRole('button', { name: '주변 극장', exact: true }));
    expect(screen.getByTestId('url').textContent).toContain('theater=72');
    const call = fetch.mock.calls.find(([url]) => url.startsWith('/api/theaters/nearby?'));
    expect(call[1].headers.Authorization).toBe('Bearer nearby-test-token');
});

test('movie details retain main metadata with spaced fields instead of middle dots', async () => {
    fetch.mockImplementation(url => url === '/api/movies/41'
        ? Promise.resolve(json({ ...movie, rating: '12', releaseDate: '2026-10-01', genres: '액션, 모험', director: '감독 이름', castNames: '배우 이름' }))
        : baseFetch(url));
    mount('/movies?movie=41');
    await screen.findByText('2026.10.01 개봉');
    expect(screen.getByText('12세')).toBeTruthy();
    expect(screen.getByText('액션, 모험')).toBeTruthy();
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
