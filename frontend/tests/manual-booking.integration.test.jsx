import { useState } from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, beforeEach, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import App from '../src/App.jsx';
import { seoulDate } from '../src/booking/state.js';
import SeatPicker from '../src/booking/SeatPicker.jsx';
import { secondsRemaining } from '../src/booking/useReservationClock.js';

const nativeShowModal = HTMLDialogElement.prototype.showModal;
const nativeClose = HTMLDialogElement.prototype.close;
beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
    HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
});
afterEach(() => {
    cleanup();
    if (nativeShowModal) HTMLDialogElement.prototype.showModal = nativeShowModal;
    else delete HTMLDialogElement.prototype.showModal;
    if (nativeClose) HTMLDialogElement.prototype.close = nativeClose;
    else delete HTMLDialogElement.prototype.close;
    vi.unstubAllGlobals(); vi.unstubAllEnvs();
});
const seats = [
    { id: 1, position: 'MIDDLE_MIDDLE', row: 'A', number: 1, segment: 'left', status: 'AVAILABLE' },
    { id: 2, position: 'MIDDLE_MIDDLE', row: 'A', number: 2, segment: 'left', status: 'AVAILABLE' },
    { id: 3, position: 'MIDDLE_MIDDLE', row: 'A', number: 3, segment: 'right', status: 'HOLDING' },
    { id: 4, row: 'A', number: 4, segment: 'right', status: 'BLOCKED' },
];
function Picker() {
    const [selected, setSelected] = useState([]);
    return <SeatPicker seats={seats} selected={selected} limit={1} onChange={setSelected} />;
}
test('manual selection caps party size and never enables held or blocked seats', () => {
    render(<Picker />);
    fireEvent.click(screen.getByRole('button', { name: 'A1 좌석' }));
    expect(screen.getByRole('button', { name: 'A1 좌석' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'A2 좌석' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'A3 선택 불가' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'A4 선택 불가' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: 'A2 좌석' }));
    expect(screen.getByRole('button', { name: 'A2 좌석' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText(/선택만으로 좌석이 확보되지 않습니다/)).toBeTruthy();
});
test('countdown uses server time plus elapsed monotonic time and clamps expiration', () => {
    const reservation = { expiresAt: '2026-10-01T09:05:00+09:00', serverTime: '2026-10-01T09:03:00+09:00' };
    expect(secondsRemaining(reservation, 1000, 16000)).toBe(105);
    expect(secondsRemaining(reservation, 1000, 121000)).toBe(0);
    expect(secondsRemaining(reservation, 1000, 999000)).toBe(0);
});

const member = { id: 1, nickname: '관객', birthDate: '1990-01-01', address: '서울', preferredTheaters: [1,2,3].map(theaterId => ({ theaterId })), preferredSeats: [{ position: 'MIDDLE_MIDDLE', priority: 1 }] };
const day = seoulDate(new Date(Date.now()+86400000));
const show = { id: 91, movieId: 41, theaterId: 71, screenName: '1관', startTime: `${day}T23:00:00+09:00`, endTime: `${day}T23:59:00+09:00`, availableSeats: 2, totalSeats: 4, pricePerPerson: 10000, layoutComplete: true };
const reservation = { id: 501, groupId: 401, status: 'PENDING', movieTitle: '서울의 밤', theaterName: '서울 극장', screenName: '1관', startTime: show.startTime, endTime: show.endTime,
    seatIds: [1,2], seatLabels: ['A1','A2'], totalAmount: 18000, expiresAt: `${day}T09:05:00+09:00`, serverTime: `${day}T09:00:00+09:00` };
let saved, group, paymentStatus, attempts;
const response = value => new Response(JSON.stringify(value));
function payment() { return { paymentId: paymentStatus ? 601 : null, status: paymentStatus, amount: paymentStatus ? 18000 : null, reservation: saved,
    ticket: paymentStatus === 'SUCCESS' ? { ticketId: 701, ticketNumber: 'ST-TEST' } : null }; }
async function api(url, options = {}) {
    if(url==='/api/booking-groups/401/waiting-queues') return response({groupId:401,groupStatus:'HOLDING',activeReservationId:saved?.id,items:[],choices:[]});
    if (url === '/api/users/me') return response(member);
    if (url === '/api/auth/login') return response({ accessToken: 'test-token' });
    if (url.startsWith('/api/theaters?')) return response({ items: [], totalElements: 0 });
    if (url === '/api/theaters/71') return response({ id: 71, name: '서울 극장' });
    if (url.startsWith('/api/theaters/71/movies')) return response({ items: [{ movieId: 41, title: '서울의 밤', rating: 'ALL' }] });
    if (url.startsWith('/api/showtimes?')) return response({ items: [show] });
    if (url === '/api/showtimes/91/seats') return response({ seats });
    if (url === '/api/booking-groups') { group = { id: 401, ...JSON.parse(options.body) }; return response(group); }
    if (url === '/api/booking-groups/401') return response({ ...group, activeReservationId: saved?.id });
    if (url === '/api/booking-groups/401/manual-hold') { saved = { ...reservation }; return response(saved); }
    if (url === '/api/reservations/501/payment') return response(payment());
    if (url === '/api/reservations/501/mock-payments') { attempts++; paymentStatus = attempts === 1 ? 'FAILED' : 'SUCCESS';
        saved = { ...saved, status: attempts === 1 ? 'PENDING' : 'CONFIRMED' }; return response(payment()); }
    if (url === '/api/reservations/501/cancel') { saved = { ...saved, status: 'CANCELLED' }; paymentStatus = 'CANCELLED'; return response(saved); }
    if (url === '/api/tickets') return response([]);
    throw new Error(`Unexpected API ${url}`);
}
beforeEach(() => { localStorage.clear(); sessionStorage.clear(); localStorage.setItem('accessToken','test-token'); saved = null; group = null; paymentStatus = null; attempts = 0; });
function Probe() { const location = useLocation(); return <output data-testid="path">{location.pathname}{location.search}</output>; }
const initial = `/theaters?theater=71&movie=41&showtime=91&date=${day}&entry=THEATER_NORMAL&adult=1&youth=1`;
function mount(path=initial) { return render(<MemoryRouter initialEntries={[path]}><App /><Probe /></MemoryRouter>); }
async function selectAndHold() {
    expect((await screen.findByLabelText('관람 인원')).textContent).toContain('총인원2명');
    expect(screen.queryByRole('combobox', { name:'성인 인원' })).toBeNull();
    expect(screen.queryByRole('combobox', { name:'청소년 인원' })).toBeNull();
    
    fireEvent.click(await screen.findByRole('button',{ name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button',{ name: 'A2 좌석' }));
    fireEvent.click(screen.getByRole('button',{ name: '선택한 좌석 5분 선점' }));
    await screen.findByRole('heading',{ name: '좌석을 선점했습니다' });
}
test('real route connects audience, seats, hold, reload, payment failure/retry and whole cancellation', async () => {
    vi.stubGlobal('fetch',vi.fn(api)); mount(); await selectAndHold();
    const create = fetch.mock.calls.find(([url])=>url==='/api/booking-groups');
    expect(JSON.parse(create[1].body).audience).toEqual({ adultCount:1,youthCount:1 });
    expect(create[1].headers['Idempotency-Key']).toMatch(/^[a-z0-9-]{16,64}$/);
    const path = screen.getByTestId('path').textContent; cleanup(); mount(path);
    await screen.findByRole('timer');
    fireEvent.click(screen.getByRole('button',{ name:'모의결제' }));
    await screen.findByText(/모의결제에 실패했습니다/);
    fireEvent.click(screen.getByRole('button',{ name:'모의결제' }));
    await screen.findByRole('heading',{ name:'예매가 완료되었습니다' });
    expect(screen.getByText('ST-TEST')).toBeTruthy();
    const pays = fetch.mock.calls.filter(([url])=>url.endsWith('/mock-payments'));
    expect(pays[0][1].headers['Idempotency-Key']).not.toBe(pays[1][1].headers['Idempotency-Key']);
    expect(JSON.parse(pays[0][1].body)).toEqual({paymentMethod:'MOCK',simulateFailure:false});
    fireEvent.click(screen.getByRole('button',{name:'예매 전체 취소'}));
    expect(screen.getByRole('dialog', { name: '예매를 취소할까요?' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '유지하기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(fetch.mock.calls.some(([url])=>url.endsWith('/cancel'))).toBe(false);
    fireEvent.click(screen.getByRole('button',{name:'예매 전체 취소'}));
    fireEvent.click(screen.getByRole('button',{name:'전체 취소 확정'}));
    await screen.findByRole('heading',{name:'예매가 취소되었습니다'});
});
test('network failure retries the same hold identity without showing premature success', async () => {
    let calls=0;
    vi.stubGlobal('fetch',vi.fn((url,options)=> { if(url.endsWith('/manual-hold') && ++calls===1) throw new TypeError('연결 끊김'); return api(url,options); }));
    mount(initial.replace('&youth=1','&youth=0'));
     fireEvent.click(await screen.findByRole('button',{name:'A1 좌석'}));
    fireEvent.click(screen.getByRole('button',{name:'선택한 좌석 5분 선점'}));
    await screen.findByText('연결 끊김'); expect(screen.queryByText('좌석을 선점했습니다')).toBeNull();
    expect(screen.getByTestId('path').textContent).toContain('group=401');
    await waitFor(() => expect(screen.getByRole('button',{name:'선택한 좌석 5분 선점'}).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'선택한 좌석 5분 선점'}));
    await screen.findByRole('heading',{name:'좌석을 선점했습니다'});
    const callsToHold=fetch.mock.calls.filter(([url])=>url.endsWith('/manual-hold'));
    expect(callsToHold[0][1].headers['Idempotency-Key']).toBe(callsToHold[1][1].headers['Idempotency-Key']);
});
test('expired restored reservation offers restart and never offers payment', async () => {
    saved={...reservation,status:'EXPIRED'}; group={id:401}; vi.stubGlobal('fetch',vi.fn(api));
    mount('/theaters?entry=THEATER_NORMAL&group=401&reservation=501');
    await screen.findByRole('heading',{name:'선점 시간이 만료되었습니다'});
    expect(screen.queryByRole('button',{name:'모의결제'})).toBeNull();
});
test('foreign reservation restoration shows the server ownership error without details', async () => {
    vi.stubGlobal('fetch',vi.fn((url,options)=>url==='/api/booking-groups/401' ? Promise.resolve(new Response(JSON.stringify({message:'관람 요청을 찾을 수 없습니다.'}),{status:404})) : api(url,options)));
    mount('/theaters?entry=THEATER_NORMAL&group=401&reservation=501');
    await screen.findByText('관람 요청을 찾을 수 없습니다.'); expect(screen.queryByText('서울의 밤')).toBeNull();
});
test('unknown prices disable hold rather than inventing an amount', async () => {
    vi.stubGlobal('fetch',vi.fn((url,options)=>url.startsWith('/api/showtimes?')? Promise.resolve(response({items:[{...show,pricePerPerson:null}]})) : api(url,options)));
    mount(); await screen.findByText('회차 가격을 확인할 수 없어 예매할 수 없습니다.');
    expect(screen.getByRole('button',{name:'선택한 좌석 5분 선점'}).disabled).toBe(true);
});


test('manual exact-seat waiting survives reload, changes seats and switches to a free-seat hold', async () => {
    let queue = null;
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
        if (url === '/api/booking-groups/401/waiting-queues') {
            if (options.method === 'POST') {
                const body = JSON.parse(options.body);
                expect(body.seatZone).toBeUndefined();
                expect(body.showtimeIds).toEqual([91]);
                queue = { id: 701, status: 'WAITING', aheadCount: 2, seatIds: [...body.seatIds].sort((a, b) => a - b), seatLabels: [...body.seatIds].sort((a, b) => a - b).map(id => 'A' + id) };
            }
            return response({ items: queue ? [queue] : [], groupStatus: 'ACTIVE' });
        }
        return api(url, options);
    }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: 'A3 대기 가능' }));
    expect(screen.getByRole('button', { name: 'A4 선택 불가' }).disabled).toBe(true);
    expect(screen.queryByRole('combobox', { name: '대기할 구역' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석 대기 신청' }));
    await screen.findByText('A1, A3 대기 중 · 앞선 신청 2건');
    expect(fetch.mock.calls.some(([url]) => url.endsWith('/manual-hold'))).toBe(false);
    const path = screen.getByTestId('path').textContent.replace(/&seats=[^&]*/, '');
    cleanup(); mount(path);
    await screen.findByText('A1, A3 대기 중 · 앞선 신청 2건');
    expect((await screen.findByRole('button', { name: 'A3 대기 가능' })).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: '선택한 좌석 대기 중' }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: 'A2 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석으로 대기 변경' }));
    await screen.findByText('A2, A3 대기 중 · 앞선 신청 2건');
    fireEvent.click(screen.getByRole('button', { name: 'A3 대기 가능' }));
    fireEvent.click(screen.getByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석 5분 선점' }));
    await screen.findByRole('heading', { name: '좌석을 선점했습니다' });
    expect(fetch.mock.calls.filter(([url, options]) => url === '/api/booking-groups' && options.method === 'POST')).toHaveLength(1);
    const waits = fetch.mock.calls.filter(([url, options]) => url.endsWith('/waiting-queues') && options.method === 'POST');
    expect(waits).toHaveLength(2);
    expect(waits[0][1].headers['Idempotency-Key']).not.toBe(waits[1][1].headers['Idempotency-Key']);
});

test('cross-zone waiting is blocked without sending a registration', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
        if (url === '/api/showtimes/91/seats') return response({ seats: seats.map(seat => seat.id === 3 ? { ...seat, position: 'SIDE_MIDDLE' } : seat) });
        return api(url, options);
    }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: 'A3 대기 가능' }));
    expect(screen.getByText('대기 좌석은 같은 구역 안에서 선택해주세요.')).toBeTruthy();
    const submit = screen.getByRole('button', { name: '선택한 좌석 대기 신청' });
    expect(submit.disabled).toBe(true);
    fireEvent.click(submit);
    expect(fetch.mock.calls.some(([url, options]) => url.endsWith('/waiting-queues') && options?.method === 'POST')).toBe(false);
});

test('duplicate show waiting explains the rule in a dismissible modal', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
        if (url.endsWith('/waiting-queues') && options.method === 'POST')
            return new Response(JSON.stringify({ message: '이미 이 회차에 대기 중입니다.', code: 'WAITING_SHOWTIME_CONFLICT' }), { status: 409 });
        return api(url, options);
    }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'A1 좌석' }));
    fireEvent.click(screen.getByRole('button', { name: 'A3 대기 가능' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석 대기 신청' }));
    await screen.findByRole('dialog', { name: '이미 신청한 회차입니다' });
    expect(screen.getByText(/같은 회차에는 한 구역만 대기할 수 있습니다/)).toBeTruthy();
    expect(screen.getByRole('link', { name: '내 대기 및 선점 보기' }).getAttribute('href')).toBe('/bookings');
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(screen.queryByRole('dialog', { name: '이미 신청한 회차입니다' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석 대기 신청' }));
    const reopened = await screen.findByRole('dialog', { name: '이미 신청한 회차입니다' });
    fireEvent(reopened, new Event('cancel', { bubbles: true, cancelable: true }));
    expect(screen.queryByRole('dialog', { name: '이미 신청한 회차입니다' })).toBeNull();
});

test('fully booked show still allows selecting reserved seats to wait', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
        if (url.startsWith('/api/showtimes?')) return response({ items: [{ ...show, availableSeats: 0 }] });
        if (url === '/api/showtimes/91/seats') return response({ seats: seats.map(seat => ({ ...seat, status: seat.status === 'BLOCKED' ? 'BLOCKED' : 'RESERVED' })) });
        if (url.endsWith('/waiting-queues')) return response({ items: [{ id: 701, status: 'WAITING', seatIds: [1, 2], seatLabels: ['A1', 'A2'], aheadCount: 0 }], groupStatus: 'ACTIVE' });
        return api(url, options);
    }));
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'A1 대기 가능' }));
    fireEvent.click(screen.getByRole('button', { name: 'A2 대기 가능' }));
    fireEvent.click(screen.getByRole('button', { name: '선택한 좌석 대기 신청' }));
    await screen.findByText('A1, A2 대기 중 · 앞선 신청 0건');
    const request = fetch.mock.calls.find(([url, options]) => url.endsWith('/waiting-queues') && options.method === 'POST');
    expect(JSON.parse(request[1].body).seatIds).toEqual([1, 2]);
});
