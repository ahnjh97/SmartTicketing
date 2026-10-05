import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { AuthContext } from '../src/auth/AuthContext.js';
import RecoveryInbox from '../src/booking/RecoveryInbox.jsx';
import BookingRestorePage from '../src/pages/BookingRestorePage.jsx';
import { bookingApi } from '../src/api/booking.js';
import { notificationApi } from '../src/api/notifications.js';

vi.mock('../src/api/booking.js', () => ({ bookingApi: { mine: vi.fn(), recovery: vi.fn() } }));
vi.mock('../src/api/notifications.js', () => ({ notificationApi: { list: vi.fn(), read: vi.fn() } }));
const item = { id: 12, movieTitle: '복구할 영화', viewingDate: '2026-10-03', partySize: 2, status: 'HOLDING' };
const view = (id = 1, child = <RecoveryInbox/>) => <AuthContext.Provider value={{ user: { id } }}><MemoryRouter>{child}</MemoryRouter></AuthContext.Provider>;
beforeEach(() => { vi.resetAllMocks(); bookingApi.mine.mockResolvedValue({ items: [item], hasMore: false }); notificationApi.list.mockResolvedValue([]); notificationApi.read.mockResolvedValue(); });
afterEach(cleanup);

test('lists persisted requests and optional legacy notifications without manufacturing a link', async () => {
    notificationApi.list.mockResolvedValue([{ id: 1, message: '이전 알림', read: true }, { id: 2, message: '좌석 확보', groupId: 12, reservationId: 34, read: false }]);
    render(view());
    await screen.findByText('복구할 영화');
    expect(screen.getByText('선점 상태 확인')).toBeTruthy();
    expect(screen.getAllByRole('link')).toHaveLength(2);
    const link = screen.getByRole('link', { name: /예약 및 대기로 이동/ });
    expect(link.getAttribute('href')).toBe('/booking/restore?group=12');
    notificationApi.read.mockRejectedValue(new Error('offline'));
    fireEvent.click(link);
    await waitFor(() => expect(notificationApi.read).toHaveBeenCalledWith(2));
});

test('old account responses cannot appear for a newly signed-in member', async () => {
    let finish;
    bookingApi.mine.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const mounted = render(view(1));
    await waitFor(() => expect(finish).toBeTruthy());
    bookingApi.mine.mockResolvedValue({ items: [], hasMore: false });
    mounted.rerender(view(2));
    await screen.findByText(/저장된 예매 요청이 없습니다/);
    await act(async () => finish({ items: [item], hasMore: false }));
    expect(screen.queryByText('복구할 영화')).toBeNull();
});

test('a notification error leaves recovery usable and retry recovers the inbox', async () => {
    notificationApi.list.mockRejectedValueOnce(new Error('알림 통신 실패'));
    render(view());
    await screen.findByText('복구할 영화');
    expect(screen.getByRole('alert').textContent).toContain('알림 통신 실패');
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await screen.findByText('아직 알림이 없습니다.');
});

test('cursor pagination can return to the newest page', async () => {
    bookingApi.mine.mockImplementation(before => Promise.resolve({ items: [before ? { ...item, id: 2, movieTitle: '이전 영화' } : item], hasMore: !before }));
    render(view());
    await screen.findByText('복구할 영화');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('이전 영화');
    expect(bookingApi.mine.mock.calls.at(-1)[0]).toBe(12);
    fireEvent.click(screen.getByRole('button', { name: '이전' }));
    await screen.findByText('복구할 영화');
});

test('foreign or missing recovery group shows server denial without navigation', async () => {
    bookingApi.recovery.mockRejectedValue(new Error('관람 요청을 찾을 수 없습니다.'));
    render(<AuthContext.Provider value={{ user: { id: 1 } }}><MemoryRouter initialEntries={['/booking/restore?group=88']}><BookingRestorePage/></MemoryRouter></AuthContext.Provider>);
    await screen.findByRole('alert');
    expect(screen.getByRole('alert').textContent).toContain('찾을 수 없습니다');
    expect(screen.getByRole('link', { name: '내 대기 및 선점' }).getAttribute('href')).toBe('/bookings');
});
