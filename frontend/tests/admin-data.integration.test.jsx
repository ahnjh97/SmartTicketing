import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AdminDataPage from '../src/pages/AdminDataPage.jsx';
import { adminApi } from '../src/api/admin.js';
import { MemoryRouter, useLocation } from 'react-router-dom';
import CommonHeader from '../src/components/CommonHeader.jsx';
import AdminRouteGuard from '../src/components/AdminRouteGuard.jsx';
import { AuthContext } from '../src/auth/AuthContext.js';

vi.mock('../src/api/admin.js', () => ({ adminApi: Object.fromEntries(['browse', 'bookingPreview', 'bookingExecute', 'summary', 'list', 'preview', 'delete', 'seats', 'edit', 'collectMovies', 'collectTheaters', 'prepare'].map(name => [name, vi.fn()])) }));
const show = { id: 7, title: '테스트 영화', theater_name: '테스트 극장', screen_name: '1관', start_time: '2026-10-10T12:00:00', available_seats: 1, total_seats: 2, status: 'SCHEDULED', price_per_person: 10000 };
beforeEach(() => {
    vi.resetAllMocks();
    adminApi.browse.mockResolvedValue({ theaters: [{ id: 5, name: 'CGV 테스트' }], movies: [{ id: 6, title: '목록 영화' }], dates: [{ date: '2026-10-10' }] });
    adminApi.bookingPreview.mockResolvedValue({ reservations: 1, waitingQueues: 0, seats: ['A1', 'A2'], counts: { reservations: 1, tickets: 1 }, fingerprint: 'booking-snapshot' });
    adminApi.bookingExecute.mockResolvedValue({ reservations: 1 });
    adminApi.summary.mockResolvedValue({ movies: 2, theaters: 2, screens: 2, showtimes: 2, seats: 4, showtime_seats: 4 });
    adminApi.list.mockResolvedValue({ items: [show], total: 1 });
    adminApi.preview.mockResolvedValue({ targetCount: 1, counts: { showtimes: 1, reservations: 1, payments: 1 }, hasBookings: true, fingerprint: 'snapshot' });
    adminApi.delete.mockResolvedValue({ targetCount: 1 });
});
afterEach(cleanup);
async function unlock() {
    render(<AdminDataPage />);
    await screen.findByText('테스트 영화');
}

test('opens without a key and reports disabled management endpoints', async () => {
    adminApi.summary.mockRejectedValue(new Error('관리 요청에 실패했습니다. (404)'));
    render(<AdminDataPage />);
    expect((await screen.findByRole('alert')).textContent).toContain('관리 요청에 실패했습니다. (404)');
    expect(screen.queryByLabelText('관리자 키')).toBeNull();
    expect(screen.getByRole('button', { name: '상영회차 전부 삭제' }).disabled).toBe(true);
});

test('selected deletion requires preview, explicit text and booking consent', async () => {
    await unlock();
    fireEvent.click(screen.getByLabelText('7 선택'));
    fireEvent.click(screen.getByRole('button', { name: '선택 삭제', exact: true }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenCalledWith({ kind: 'showtimes', mode: 'selected', ids: [7] });
    const execute = screen.getByRole('button', { name: '영구 삭제 실행' });
    expect(execute.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('확인 문구 ‘삭제’ 입력'), { target: { value: '삭제' } });
    expect(execute.disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('연결된 예약·결제·티켓·대기 데이터 삭제에 동의합니다.'));
    fireEvent.click(execute);
    await waitFor(() => expect(adminApi.delete).toHaveBeenCalledWith({ scope: { kind: 'showtimes', mode: 'selected', ids: [7] }, fingerprint: 'snapshot', confirmation: '삭제', includeBookings: true }));
    await screen.findByText(/삭제 완료/);
});

test('all deletion omits filters while filtered deletion uses the applied query', async () => {
    await unlock();
    fireEvent.click(await screen.findByRole('button', { name: 'CGV 테스트', exact: true }));
    await waitFor(() => expect(screen.getByRole('button', { name: '목록 전체 삭제' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '목록 전체 삭제' }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenLastCalledWith({ kind: 'showtimes', mode: 'filtered', theaterId: 5 });
    fireEvent.click(screen.getByRole('button', { name: '취소', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '상영회차 전부 삭제' }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenLastCalledWith({ kind: 'showtimes', mode: 'all' });
    expect(adminApi.delete).not.toHaveBeenCalled();
});

test('seat map distinguishes missing inventory and displays reservation details', async () => {
    adminApi.seats.mockResolvedValue([{ id: 1, seat_row: 'A', seat_number: 1, status: 'HOLDING', reservation_id: 12, hold_expired_at: '2026-10-10T12:10:00' }, { id: 2, seat_row: 'A', seat_number: 2, status: null }]);
    await unlock(); fireEvent.click(screen.getByRole('button', { name: '좌석 현황', exact: true }));
    fireEvent.click(await screen.findByRole('button', { name: 'A1 선점 중' }));
    expect(screen.getByText('예약 ID: 12')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'A2 재고 미생성' })).toBeTruthy();
});

test('failed deletion retains review and shows server conflict instead of success', async () => {
    adminApi.preview.mockResolvedValue({ targetCount: 1, counts: { showtimes: 1 }, hasBookings: false, fingerprint: 'snapshot' });
    adminApi.delete.mockRejectedValue(new Error('데이터가 변경되었습니다. 삭제 범위를 다시 확인해주세요.'));
    await unlock(); fireEvent.click(screen.getByRole('button', { name: '상영회차 전부 삭제' }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    fireEvent.change(screen.getByLabelText('확인 문구 ‘삭제’ 입력'), { target: { value: '삭제' } });
    fireEvent.click(screen.getByRole('button', { name: '영구 삭제 실행' }));
    expect((await screen.findByRole('alert')).textContent).toContain('데이터가 변경');
    expect(screen.queryByText(/삭제 완료/)).toBeNull();
});

function Location() { return <output>{useLocation().pathname}</output>; }
test('ordinary users have no management button and direct navigation is redirected', () => {
    render(<MemoryRouter initialEntries={['/admin/data']}><AuthContext.Provider value={{ user: { id: 2, admin: false } }}>
        <CommonHeader user={{ id: 2 }} onLogout={() => {}} />
        <AdminRouteGuard><div>관리 화면 내용</div></AdminRouteGuard><Location />
    </AuthContext.Provider></MemoryRouter>);
    expect(screen.queryByRole('button', { name: '데이터 관리', exact: true })).toBeNull();
    expect(screen.queryByText('관리 화면 내용')).toBeNull();
    expect(screen.queryByText('/admin/data')).toBeNull();
    expect(adminApi.summary).not.toHaveBeenCalled();
});
test('header places data management immediately left of tickets and opens its route', () => {
    render(<MemoryRouter><CommonHeader user={{ id: 1, admin: true }} onLogout={() => {}} /><Location /></MemoryRouter>);
    const management = screen.getByRole('button', { name: '데이터 관리', exact: true });
    expect(management.nextElementSibling.getAttribute('aria-label')).toBe('내 티켓 열기');
    fireEvent.click(management);
    expect(screen.getByText('/admin/data')).toBeTruthy();
});


test('buttons choose theater movie and date without search or ID fields', async () => {
    await unlock();
    expect(screen.queryByLabelText('검색')).toBeNull();
    expect(screen.queryByLabelText('영화관 ID')).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'CGV 테스트', exact: true }));
    await waitFor(() => expect(adminApi.list).toHaveBeenLastCalledWith('showtimes', { theaterId: 5, page: 0 }, expect.any(AbortSignal)));
    fireEvent.click(screen.getByRole('button', { name: '목록 영화', exact: true }));
    await waitFor(() => expect(adminApi.list).toHaveBeenLastCalledWith('showtimes', { theaterId: 5, movieId: 6, page: 0 }, expect.any(AbortSignal)));
    fireEvent.click(screen.getByRole('button', { name: '2026-10-10', exact: true }));
    await waitFor(() => expect(adminApi.list).toHaveBeenLastCalledWith('showtimes', { theaterId: 5, movieId: 6, date: '2026-10-10', page: 0 }, expect.any(AbortSignal)));
});

test('selected seat cancellation previews expanded reservation seats and requires confirmation', async () => {
    adminApi.seats.mockResolvedValue([{ id: 1, seat_row: 'A', seat_number: 1, status: 'RESERVED', reservation_id: 12 }]);
    await unlock(); fireEvent.click(screen.getByRole('button', { name: '좌석 현황', exact: true }));
    fireEvent.click(await screen.findByRole('button', { name: 'A1 예약 완료' }));
    fireEvent.click(screen.getByRole('button', { name: '선택 좌석 예매 취소', exact: true }));
    await screen.findByRole('region', { name: '예매 처리 확인' });
    expect(adminApi.bookingPreview).toHaveBeenCalledWith({ showtimeId: 7, action: 'cancel', mode: 'selected', seatIds: [1] });
    expect(screen.getByText(/대상 좌석: A1, A2/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '확인 후 실행' }).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('예매 처리 확인 문구 ‘취소’ 입력'), { target: { value: '취소' } });
    fireEvent.click(screen.getByRole('button', { name: '확인 후 실행' }));
    await waitFor(() => expect(adminApi.bookingExecute).toHaveBeenCalledWith({ scope: { showtimeId: 7, action: 'cancel', mode: 'selected', seatIds: [1] }, fingerprint: 'booking-snapshot', confirmation: '취소' }));
});

test('all record deletion stays scoped to the open show and a conflict requires new preview', async () => {
    adminApi.seats.mockResolvedValue([]);
    adminApi.bookingExecute.mockRejectedValue(new Error('예매 상태가 변경되었습니다.'));
    await unlock(); fireEvent.click(screen.getByRole('button', { name: '좌석 현황', exact: true }));
    await waitFor(() => expect(screen.getByRole('button', { name: '이 회차 전체 예매 기록 삭제' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '이 회차 전체 예매 기록 삭제' }));
    await screen.findByRole('region', { name: '예매 처리 확인' });
    expect(adminApi.bookingPreview).toHaveBeenCalledWith({ showtimeId: 7, action: 'purge', mode: 'all' });
    fireEvent.change(screen.getByLabelText('예매 처리 확인 문구 ‘삭제’ 입력'), { target: { value: '삭제' } });
    fireEvent.click(screen.getByRole('button', { name: '확인 후 실행' }));
    await screen.findByText('예매 상태가 변경되었습니다.');
    expect(screen.queryByRole('region', { name: '예매 처리 확인' })).toBeNull();
});
