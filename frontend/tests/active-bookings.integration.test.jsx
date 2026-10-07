import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ActiveBookingsPage from '../src/pages/ActiveBookingsPage.jsx';
import { bookingApi } from '../src/api/booking.js';
const auth = vi.hoisted(() => ({ user: { id: 1 } }));
vi.mock('../src/hooks/useAuth.js', () => ({ default: () => auth }));
vi.mock('../src/api/booking.js', () => ({ bookingApi: { active: vi.fn(), history: vi.fn(), mine: vi.fn(), waiting: vi.fn(), reservation: vi.fn() } }));
const queue = { id: 1, status: 'WAITING', theaterName: '강남 영화관', screenName: '1관', startTime: '2026-10-05T20:00:00+09:00', aheadCount: 3 };
const waiting = { id: 8, movieTitle: '대기 영화', partySize: 2, kind: 'waiting', queues: [queue] };
const holding = { id: 10, movieTitle: '선점 영화', partySize: 2, kind: 'holding', reservation: {
    theaterName: '용산 영화관', screenName: '2관', startTime: queue.startTime, seatLabels: ['C4', 'C5'],
    serverTime: '2026-10-05T10:00:00+09:00', expiresAt: '2026-10-05T10:05:00+09:00',
} };
const view = () => <MemoryRouter><ActiveBookingsPage /></MemoryRouter>;
beforeEach(() => { vi.resetAllMocks(); auth.user = { id: 1 }; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test('loads holdings and queues with one request and no history or per-group calls', async () => {
    bookingApi.active.mockResolvedValue([holding, waiting]);
    render(view());
    await screen.findByText('대기 영화');
    expect(screen.getByText('순번 4번')).toBeTruthy();
    expect(screen.getByText('C4, C5')).toBeTruthy();
    expect(screen.getByRole('timer').textContent).toBe('5:00');
    expect(screen.getByRole('link', { name: '선점 영화 C4, C5 예매 확인' }).getAttribute('href')).toBe('/booking/restore?group=10');
    expect(screen.getByRole('link', { name: '스마트예매에서 확인' }).getAttribute('href')).toBe('/booking/restore?group=8');
    expect(bookingApi.active).toHaveBeenCalledTimes(1);
    expect(bookingApi.mine).not.toHaveBeenCalled();
    expect(bookingApi.waiting).not.toHaveBeenCalled();
    expect(bookingApi.reservation).not.toHaveBeenCalled();
});
test('refresh removes bookings that are no longer active', async () => {
    bookingApi.active.mockResolvedValue([waiting]);
    render(view());
    await screen.findByText('순번 4번');
    bookingApi.active.mockResolvedValue([]);
    fireEvent.focus(window);
    await waitFor(() => expect(screen.queryByText('대기 영화')).toBeNull());
    expect(screen.queryByText('대기 영화')).toBeNull();
});
test('refresh failure preserves the last known list with a visible error', async () => {
    bookingApi.active.mockResolvedValue([waiting]);
    render(view());
    await screen.findByText('순번 4번');
    bookingApi.active.mockRejectedValue(new Error('offline'));
    fireEvent.focus(window);
    await screen.findByRole('alert');
    expect(screen.getByText('대기 영화')).toBeTruthy();
    expect(screen.queryByText('대기 중이거나 선점한 좌석이 없습니다.')).toBeNull();
});
test('initial failure shows retry without a misleading empty state', async () => {
    bookingApi.active.mockRejectedValue(new Error('offline'));
    render(view());
    await screen.findByRole('alert');
    expect(screen.queryByText('대기 중이거나 선점한 좌석이 없습니다.')).toBeNull();
    bookingApi.active.mockResolvedValue([]);
    fireEvent.focus(window);
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(screen.queryByRole('alert')).toBeNull();
});
test('account change ignores a previous account response and aborts its request', async () => {
    let finish;
    bookingApi.active.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue([]);
    const { rerender } = render(view());
    await waitFor(() => expect(finish).toBeTruthy());
    const oldSignal = bookingApi.active.mock.calls[0][0];
    auth.user = { id: 2 };
    rerender(view());
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    await act(async () => finish([holding]));
    expect(oldSignal.aborted).toBe(true);
    expect(screen.queryByText('선점 영화')).toBeNull();
});
test('focus refreshes position without duplicating an in-flight request', async () => {
    let finish;
    bookingApi.active.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    render(view());
    await waitFor(() => expect(finish).toBeTruthy());
    fireEvent.focus(window);
    expect(bookingApi.active).toHaveBeenCalledTimes(1);
    await act(async () => finish([waiting]));
    bookingApi.active.mockResolvedValue([{ ...waiting, queues: [{ ...queue, aheadCount: 1 }] }]);
    fireEvent.focus(window);
    await screen.findByText('순번 2번');
});


test('independent smart entries restore their candidate while manual waiting offers seat changes', async () => {
    bookingApi.active.mockResolvedValue([
        { ...holding, entryPoint: 'THEATER_SMART', candidateKind: 'PREFERRED' },
        { ...waiting, entryPoint: 'MOVIE_SMART' },
        { ...waiting, id: 99, entryPoint: 'THEATER_NORMAL', movieTitle: '일반 영화' },
    ]);
    render(view());
    await screen.findByText('일반 영화');
    expect(screen.getByRole('link', { name: '스마트예매에서 확인' }).getAttribute('href')).toBe('/booking/restore?group=8');
    expect(screen.getByRole('link', { name: '좌석 확인 · 대기 변경' }).getAttribute('href')).toBe('/booking/restore?group=99');
    expect(screen.getByRole('heading', { name: '스마트 예매 2개' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: '일반 예매 1건' })).toBeTruthy();
});

test('history replaces the current list, paginates and returns to active bookings', async () => {
    bookingApi.active.mockResolvedValue([holding]);
    const ended = { id: 'queue-12', groupId: 12, movieTitle: '지난 영화', partySize: 2,
        entryPoint: 'THEATER_SMART', kind: 'waiting', status: 'CANCELLED', zone: 'MIDDLE_MIDDLE',
        theaterName: '강남 영화관', screenName: '1관', startTime: queue.startTime };
    bookingApi.history.mockResolvedValueOnce({ items: [ended], nextBefore: 12 })
        .mockResolvedValueOnce({ items: [{ ...ended, id: 'reservation-3', movieTitle: '만료 영화', kind: 'holding', status: 'EXPIRED' }], nextBefore: null });
    render(view());
    await screen.findByText('선점 영화');
    expect(bookingApi.history).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '지난 내역' }));
    await screen.findByText('지난 영화');
    expect(screen.queryByText('선점 영화')).toBeNull();
    expect(screen.getByText('대기 취소')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    await screen.findByText('만료 영화');
    expect(screen.getByText('선점 만료')).toBeTruthy();
    expect(screen.getByText('지난 영화')).toBeTruthy();
    expect(bookingApi.history.mock.calls[1][0]).toBe(12);
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
    expect(screen.queryByRole('timer')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '진행 중 보기' }));
    await screen.findByText('선점 영화');
});

test('history failure can retry and an unmounted history request is aborted', async () => {
    bookingApi.active.mockResolvedValue([]);
    bookingApi.history.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ items: [], nextBefore: null });
    render(view());
    fireEvent.click(screen.getByRole('button', { name: '지난 내역' }));
    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('취소·만료된 내역이 없습니다.');
    fireEvent.click(screen.getByRole('button', { name: '진행 중 보기' }));
    expect(bookingApi.history.mock.calls[1][1].aborted).toBe(true);
});
