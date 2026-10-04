import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ActiveBookingsPage from '../src/pages/ActiveBookingsPage.jsx';
import { bookingApi } from '../src/api/booking.js';
const auth = vi.hoisted(() => ({ user: { id: 1 } }));
vi.mock('../src/hooks/useAuth.js', () => ({ default: () => auth }));
vi.mock('../src/api/booking.js', () => ({ bookingApi: { active: vi.fn(), mine: vi.fn(), waiting: vi.fn(), reservation: vi.fn() } }));
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
    expect(screen.getByText('앞 3명')).toBeTruthy();
    expect(screen.getByText('C4, C5')).toBeTruthy();
    expect(screen.getByRole('timer').textContent).toBe('5:00');
    expect(screen.getByRole('link', { name: '결제하기' }).getAttribute('href')).toBe('/booking/restore?group=10');
    expect(screen.getByRole('link', { name: '대기 확인' }).getAttribute('href')).toBe('/booking/restore?group=8');
    expect(bookingApi.active).toHaveBeenCalledTimes(1);
    expect(bookingApi.mine).not.toHaveBeenCalled();
    expect(bookingApi.waiting).not.toHaveBeenCalled();
    expect(bookingApi.reservation).not.toHaveBeenCalled();
});
test('refresh removes bookings that are no longer active', async () => {
    bookingApi.active.mockResolvedValue([waiting]);
    render(view());
    await screen.findByText('앞 3명');
    bookingApi.active.mockResolvedValue([]);
    fireEvent.click(screen.getByRole('button', { name: '대기 및 선점 새로고침' }));
    await screen.findByText('대기 중이거나 선점한 좌석이 없습니다.');
    expect(screen.queryByText('대기 영화')).toBeNull();
});
test('refresh failure preserves the last known list with a visible error', async () => {
    bookingApi.active.mockResolvedValue([waiting]);
    render(view());
    await screen.findByText('앞 3명');
    bookingApi.active.mockRejectedValue(new Error('offline'));
    fireEvent.click(screen.getByRole('button', { name: '대기 및 선점 새로고침' }));
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
    fireEvent.click(screen.getByRole('button', { name: '대기 및 선점 새로고침' }));
    await screen.findByText('대기 중이거나 선점한 좌석이 없습니다.');
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
    await screen.findByText('대기 중이거나 선점한 좌석이 없습니다.');
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
    await screen.findByText('앞 1명');
});
