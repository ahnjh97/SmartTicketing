import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AdminDataPage from '../src/pages/AdminDataPage.jsx';
import { adminApi } from '../src/api/admin.js';
import { seoulDate } from '../src/booking/state.js';
import { MemoryRouter, useLocation } from 'react-router-dom';
import CommonHeader from '../src/components/CommonHeader.jsx';
import AdminRouteGuard from '../src/components/AdminRouteGuard.jsx';
import { AuthContext } from '../src/auth/AuthContext.js';

vi.mock('../src/api/admin.js', () => ({ adminApi: Object.fromEntries(['collectionStatus', 'task', 'dates', 'browse', 'bookingPreview', 'bookingExecute', 'summary', 'list', 'preview', 'delete', 'seats', 'edit', 'collectMovies', 'collectTheaters', 'prepare'].map(name => [name, vi.fn()])) }));
const show = { id: 7, title: '테스트 영화', theater_name: '테스트 극장', screen_name: '1관', start_time: '2026-10-10T12:00:00', available_seats: 1, total_seats: 2, status: 'SCHEDULED', price_per_person: 10000 };
beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
    HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
    vi.resetAllMocks();
    adminApi.task.mockResolvedValue({ state: 'IDLE' });
    adminApi.collectionStatus.mockResolvedValue([{ key: 'movies', label: '영화 수집', count: 18, expected: 20, missing: 2, detail: '설정된 영화 기준' }]);
    adminApi.browse.mockResolvedValue({ theaters: [{ id: 5, name: 'CGV 테스트', brand: 'CGV' }, { id: 8, name: '롯데 테스트', brand: 'LOTTE_CINEMA' }], movies: [{ id: 6, title: '목록 영화', movie_status: 'now' }, { id: 9, title: '예정 영화', movie_status: 'upcoming' }], dates: [{ date: '2026-10-10' }] });
    adminApi.dates.mockResolvedValue([{ date: '2026-10-10' }]);
    adminApi.bookingPreview.mockResolvedValue({ reservations: 1, waitingQueues: 0, seats: ['A1', 'A2'], counts: { reservations: 1, tickets: 1 }, fingerprint: 'booking-snapshot' });
    adminApi.bookingExecute.mockResolvedValue({ reservations: 1 });
    adminApi.summary.mockResolvedValue({ movies: 2, theaters: 2, screens: 2, showtimes: 2, seats: 4, showtime_seats: 4 });
    adminApi.list.mockResolvedValue({ items: [show], total: 1 });
    adminApi.preview.mockResolvedValue({ targetCount: 1, counts: { showtimes: 1, reservations: 1, payments: 1 }, hasBookings: true, fingerprint: 'snapshot' });
    adminApi.delete.mockResolvedValue({ targetCount: 1 });
});
afterEach(cleanup);
test('reopening management restores active task progress and blocks duplicate mutations', async () => {
    adminApi.task.mockResolvedValue({ id: 'ongoing', label: '데이터 삭제', state: 'RUNNING', completed: 12, total: 67, detail: '회차·좌석 삭제 중' });
    render(<AdminDataPage />);
    await screen.findByText(/12 \/ 67개/);
    expect(screen.getByRole('button', { name: '영화 수집', exact: true }).disabled).toBe(true);
    expect(adminApi.delete).not.toHaveBeenCalled();
});

test('showtime movie categories clear prior selections without fetching all showtimes', async () => {
    await unlock();
    expect(screen.queryByRole('button', { name: '예정 영화' })).toBeNull();
    const calls = adminApi.list.mock.calls.length;
    fireEvent.click(screen.getByRole('tab', { name: '상영 예정작' }));
    expect(screen.getByRole('button', { name: '예정 영화' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '목록 영화' })).toBeNull();
    expect(screen.queryByText('테스트 영화')).toBeNull();
    expect(screen.queryByRole('button', { name: '2026-10-10' })).toBeNull();
    expect(adminApi.list).toHaveBeenCalledTimes(calls);
});

test('movie cards switch categories and keep tab deletion separate from all movies', async () => {
    adminApi.list.mockResolvedValue({ items: [{ id: 10, title: '상영 영화', poster_url: '/poster.jpg', release_date: '2026-09-30T15:00:00.000+00:00' }], total: 1 });
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('tab', { name: '영화', exact: true }));
    await screen.findByAltText('상영 영화 포스터');
    expect(screen.getByText('2026-10-01 개봉')).toBeTruthy();
    expect(adminApi.list).toHaveBeenLastCalledWith('movies', { movieStatus: 'now', page: 0 }, expect.any(AbortSignal));
    expect(screen.queryByText('선택 후 삭제·재수집')).toBeNull();
    fireEvent.click(screen.getByLabelText('10 선택'));
    fireEvent.click(screen.getByRole('tab', { name: '상영 예정작' }));
    await waitFor(() => expect(adminApi.list).toHaveBeenLastCalledWith('movies', { movieStatus: 'upcoming', page: 0 }, expect.any(AbortSignal)));
    await waitFor(() => expect(screen.getByRole('button', { name: '현재 탭 전체 삭제' }).disabled).toBe(false));
    expect(screen.getByLabelText('10 선택').checked).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '현재 탭 전체 삭제' }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenLastCalledWith({ kind: 'movies', mode: 'filtered', movieStatus: 'upcoming' });
    fireEvent.click(screen.getByRole('button', { name: '취소', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '영화 전부 삭제' }));
    const panel = await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(panel.closest('dialog').hasAttribute('open')).toBe(true);
    expect(adminApi.preview).toHaveBeenLastCalledWith({ kind: 'movies', mode: 'all' });
    expect(adminApi.delete).not.toHaveBeenCalled();
});

test('all booking history deletion opens a modal and requires explicit confirmation', async () => {
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('button', { name: '모든 예매내역 삭제' }));
    await screen.findByRole('dialog', { name: '모든 예매내역 삭제' });
    await screen.findByRole('button', { name: '모든 예매내역 영구 삭제' });
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(adminApi.bookingPreview).toHaveBeenCalledWith({ showtimeId: 0, mode: 'global', action: 'purge' });
    expect(screen.getByRole('button', { name: '모든 예매내역 영구 삭제' }).disabled).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '모든 예매내역 영구 삭제' }));
    await waitFor(() => expect(adminApi.bookingExecute).toHaveBeenCalledWith({ scope: { showtimeId: 0, mode: 'global', action: 'purge' }, fingerprint: 'booking-snapshot', confirmation: '삭제' }));
});

test('collection buttons need no refresh option', async () => {
    adminApi.collectTheaters.mockResolvedValue({ insertedCount: 1 });
    render(<AdminDataPage />);
    expect(screen.queryByLabelText('전체 재조회')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '영화관 수집', exact: true }));
    await waitFor(() => expect(adminApi.collectTheaters).toHaveBeenCalledWith());
    await screen.findByText(/영화관 수집 처리 결과/);
});
async function unlock() {
    render(<AdminDataPage />);
    fireEvent.click(await screen.findByRole('button', { name: '목록 영화' }));
    fireEvent.click(screen.getByRole('button', { name: 'CGV 테스트' }));
    fireEvent.click(await screen.findByRole('button', { name: '2026-10-10' }));
    await screen.findByText('테스트 영화');
}

test('opens without a key and reports disabled management endpoints', async () => {
    adminApi.browse.mockRejectedValue(new Error('관리 요청에 실패했습니다. (404)'));
    render(<AdminDataPage />);
    expect((await screen.findByRole('alert')).textContent).toContain('관리 요청에 실패했습니다. (404)');
    expect(screen.queryByLabelText('관리자 키')).toBeNull();
    expect(adminApi.list).not.toHaveBeenCalled();
});

test('selected deletion requires preview and booking consent without typed text', async () => {
    await unlock();
    fireEvent.click(screen.getByLabelText('7 선택'));
    fireEvent.click(screen.getByRole('button', { name: '선택 삭제', exact: true }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenCalledWith({ kind: 'showtimes', mode: 'selected', ids: [7] });
    const execute = screen.getByRole('button', { name: '영구 삭제 실행' });
    expect(execute.disabled).toBe(true);
    expect(execute.disabled).toBe(true);
    fireEvent.click(screen.getByLabelText('연결된 예약·결제·티켓·대기 데이터 삭제에 동의합니다.'));
    fireEvent.click(execute);
    await waitFor(() => expect(adminApi.delete).toHaveBeenCalledWith({ scope: { kind: 'showtimes', mode: 'selected', ids: [7] }, fingerprint: 'snapshot', confirmation: '삭제', includeBookings: true }));
    await screen.findByText(/삭제 완료/);
});

test('all deletion omits filters while filtered deletion uses the applied query', async () => {
    await unlock();
    await waitFor(() => expect(screen.getByRole('button', { name: '목록 전체 삭제' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '목록 전체 삭제' }));
    await screen.findByRole('region', { name: '삭제 영향 확인' });
    expect(adminApi.preview).toHaveBeenLastCalledWith({ kind: 'showtimes', mode: 'filtered', theaterId: 5, movieId: 6, date: '2026-10-10' });
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


test('defaults to today after movie and theater selection and brand change clears dependent choices', async () => {
    render(<AdminDataPage />);
    await screen.findByRole('button', { name: '목록 영화' });
    expect(adminApi.list).not.toHaveBeenCalled();
    expect(adminApi.dates).not.toHaveBeenCalled();
    expect(adminApi.summary).not.toHaveBeenCalled();
    for (const name of ['모든 영화관', '모든 영화', '모든 날짜']) expect(screen.queryByRole('button', { name })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '목록 영화' }));
    expect(adminApi.list).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'CGV 테스트' }));
    const date = await screen.findByRole('button', { name: '2026-10-10' });
    expect(screen.getByRole('button', { name: seoulDate(), pressed: true })).toBeTruthy();
    await waitFor(() => expect(adminApi.list).toHaveBeenLastCalledWith('showtimes', { theaterId: 5, movieId: 6, date: seoulDate(), page: 0 }, expect.any(AbortSignal)));
    fireEvent.click(date);
    await screen.findByText('테스트 영화');
    expect(adminApi.list).toHaveBeenLastCalledWith('showtimes', { theaterId: 5, movieId: 6, date: '2026-10-10', page: 0 }, expect.any(AbortSignal));
    const calls = adminApi.list.mock.calls.length;
    fireEvent.click(screen.getByRole('tab', { name: '롯데시네마' }));
    expect(screen.queryByRole('button', { name: 'CGV 테스트' })).toBeNull();
    expect(screen.getByRole('button', { name: '롯데 테스트' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '2026-10-10' })).toBeNull();
    expect(screen.queryByText('테스트 영화')).toBeNull();
    expect(adminApi.list).toHaveBeenCalledTimes(calls);
});

test('catalog loads without waiting for global counts and has no manual edit actions', async () => {
    adminApi.summary.mockImplementation(() => new Promise(() => {}));
    adminApi.list.mockResolvedValue({ items: [{ id: 1, title: '수집 영화', is_active: true }], total: 1 });
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('tab', { name: '영화', exact: true }));
    await screen.findByText('수집 영화');
    expect(screen.queryByRole('button', { name: '수정', exact: true })).toBeNull();
    expect(adminApi.summary).not.toHaveBeenCalled();
    expect(adminApi.dates).not.toHaveBeenCalled();
    expect(adminApi.list).toHaveBeenCalledTimes(1);
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
    fireEvent.click(screen.getByRole('button', { name: '확인 후 실행' }));
    await screen.findByText('예매 상태가 변경되었습니다.');
    expect(screen.queryByRole('region', { name: '예매 처리 확인' })).toBeNull();
});


test('collection counts show deficits, do not refetch on tabs, and recover from errors', async () => {
    render(<AdminDataPage />);
    await screen.findByText('미충족 2');
    expect(screen.getByLabelText(/영화 수집: 18 \/ 20/).title).toBe('설정된 영화 기준');
    const calls = adminApi.collectionStatus.mock.calls.length;
    fireEvent.click(screen.getByRole('tab', { name: '영화', exact: true }));
    await waitFor(() => expect(adminApi.list).toHaveBeenCalled());
    expect(adminApi.collectionStatus).toHaveBeenCalledTimes(calls);
    adminApi.collectionStatus.mockRejectedValueOnce(new Error('집계 실패'));
    fireEvent.click(screen.getByRole('button', { name: '현황 새로고침' }));
    await screen.findByText(/현황을 불러오지 못했습니다/);
    expect(screen.queryByText('미충족 2')).toBeNull();
    adminApi.collectionStatus.mockResolvedValue([{ key: 'movies', label: '영화 수집', count: 20, expected: 20, missing: 0, detail: '설정된 영화 기준' }]);
    fireEvent.click(screen.getByRole('button', { name: '현황 새로고침' }));
    await screen.findByText('충족');
});


test('disabled collection button explains busy state on hover and keyboard focus without executing', async () => {
    adminApi.task.mockResolvedValue({ id: 'busy', state: 'RUNNING', label: '데이터 삭제', detail: '처리 중' });
    render(<AdminDataPage />);
    const button = screen.getByRole('button', { name: '영화 수집', exact: true });
    await waitFor(() => expect(button.disabled).toBe(true));
    const wrapper = button.parentElement;
    expect(wrapper.tabIndex).toBe(0);
    fireEvent.mouseEnter(wrapper);
    expect(screen.getByRole('tooltip').textContent).toBe('관리자 작업이 진행 중입니다.');
    fireEvent.click(button);
    expect(adminApi.collectMovies).not.toHaveBeenCalled();
    fireEvent.keyDown(wrapper, { key: 'Escape' });
    expect(screen.queryByRole('tooltip')).toBeNull();
    fireEvent.focus(wrapper);
    expect(screen.getByRole('tooltip').textContent).toBe('관리자 작업이 진행 중입니다.');
});

test('selection and consent requirements explain why destructive actions are disabled', async () => {
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('tab', { name: '영화', exact: true }));
    await screen.findByLabelText('7 선택');
    const selection = screen.getByRole('button', { name: '선택 삭제', exact: true });
    fireEvent.mouseEnter(selection.parentElement);
    expect(screen.getByRole('tooltip').textContent).toBe('삭제할 항목을 선택해주세요.');
    fireEvent.mouseLeave(selection.parentElement);
    fireEvent.click(screen.getByLabelText('7 선택'));
    expect(selection.disabled).toBe(false);
    fireEvent.click(selection);
    const execute = await screen.findByRole('button', { name: '영구 삭제 실행' });
    fireEvent.mouseEnter(execute.parentElement);
    expect(screen.getByRole('tooltip').textContent).toBe('연결된 예매 데이터 삭제에 동의해주세요.');
    fireEvent.click(execute);
    expect(adminApi.delete).not.toHaveBeenCalled();
});

test('empty global booking deletion explains the absence of records', async () => {
    adminApi.bookingPreview.mockResolvedValue({ counts: { reservations: 0, tickets: 0 }, fingerprint: 'empty' });
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('button', { name: '모든 예매내역 삭제', exact: true }));
    const execute = await screen.findByRole('button', { name: '모든 예매내역 영구 삭제' });
    expect(execute.disabled).toBe(true);
    fireEvent.mouseEnter(execute.parentElement);
    expect(screen.getByRole('tooltip').textContent).toBe('삭제할 예매 관련 기록이 없습니다.');
    fireEvent.click(execute);
    expect(adminApi.bookingExecute).not.toHaveBeenCalled();
});


test('failed date lookup offers retry instead of permanent loading and keeps today selected', async () => {
    adminApi.dates.mockRejectedValueOnce(new Error('날짜 연결 실패'));
    render(<AdminDataPage />);
    fireEvent.click(await screen.findByRole('button', { name: '목록 영화' }));
    fireEvent.click(screen.getByRole('button', { name: 'CGV 테스트' }));
    await screen.findByText(/날짜 조회 실패/);
    expect(screen.queryByText('날짜를 불러오는 중…')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '날짜 다시 불러오기' }));
    await screen.findByRole('button', { name: seoulDate(), pressed: true });
    expect(adminApi.dates).toHaveBeenCalledTimes(2);
});

test('global purge recovers from a failed preview inside the same modal', async () => {
    adminApi.bookingPreview.mockRejectedValueOnce(new Error('조회 연결 실패'));
    render(<AdminDataPage />);
    fireEvent.click(screen.getByRole('button', { name: '모든 예매내역 삭제', exact: true }));
    await screen.findByText('조회 연결 실패');
    fireEvent.click(screen.getByRole('button', { name: '삭제 대상 다시 확인' }));
    await screen.findByRole('button', { name: '모든 예매내역 영구 삭제' });
    expect(adminApi.bookingPreview).toHaveBeenCalledTimes(2);
    expect(adminApi.bookingExecute).not.toHaveBeenCalled();
});

test('conflicting catalog deletion requires a fresh fingerprint before retry', async () => {
    adminApi.preview.mockResolvedValue({ targetCount: 1, counts: { showtimes: 1 }, hasBookings: false, fingerprint: 'old' });
    adminApi.delete.mockRejectedValueOnce(new Error('삭제 대상 변경'));
    await unlock();
    fireEvent.click(screen.getByRole('button', { name: '상영회차 전부 삭제' }));
    fireEvent.click(await screen.findByRole('button', { name: '영구 삭제 실행' }));
    await screen.findByText('삭제 대상 변경');
    expect(screen.getByRole('button', { name: '영구 삭제 실행' }).disabled).toBe(true);
    adminApi.preview.mockResolvedValue({ targetCount: 1, counts: { showtimes: 1 }, hasBookings: false, fingerprint: 'fresh' });
    fireEvent.click(screen.getByRole('button', { name: '삭제 대상 다시 확인' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '영구 삭제 실행' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: '영구 삭제 실행' }));
    await waitFor(() => expect(adminApi.delete).toHaveBeenLastCalledWith(expect.objectContaining({ fingerprint: 'fresh' })));
});

test('failed seat refresh marks stale data and blocks mutations until a successful refresh', async () => {
    adminApi.seats.mockResolvedValue([{ id: 1, seat_row: 'A', seat_number: 1, status: 'RESERVED' }]);
    await unlock(); fireEvent.click(screen.getByRole('button', { name: '좌석 현황', exact: true }));
    fireEvent.click(await screen.findByRole('button', { name: 'A1 예약 완료' }));
    adminApi.seats.mockRejectedValueOnce(new Error('좌석 연결 실패'));
    fireEvent.click(screen.getByRole('button', { name: '좌석 새로고침' }));
    await screen.findByText(/표시된 좌석은 마지막 조회 정보/);
    expect(screen.getByRole('button', { name: '선택 좌석 예매 취소', exact: true }).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: '좌석 새로고침' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '선택 좌석 예매 취소', exact: true }).disabled).toBe(false));
    expect(screen.queryByText(/좌석 조회 실패/)).toBeNull();
});

test('late task polling cannot undo a completed task event', async () => {
    let resolvePoll;
    adminApi.task.mockImplementation(() => new Promise(resolve => { resolvePoll = resolve; }));
    render(<AdminDataPage />);
    window.dispatchEvent(new CustomEvent('admin-task', { detail: { id: 'job', state: 'RUNNING', label: '수집' } }));
    await waitFor(() => expect(screen.getByRole('button', { name: '영화 수집', exact: true }).disabled).toBe(true));
    window.dispatchEvent(new CustomEvent('admin-task', { detail: { id: 'job', state: 'COMPLETED', label: '수집' } }));
    resolvePoll({ id: 'job', state: 'RUNNING', label: '수집' });
    await waitFor(() => expect(screen.getByRole('button', { name: '영화 수집', exact: true }).disabled).toBe(false));
});
