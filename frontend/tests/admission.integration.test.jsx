import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import AdmissionGate from '../src/components/AdmissionGate.jsx';
import { admissionApi } from '../src/api/admission.js';
import { MemoryRouter, useLocation } from 'react-router-dom';
vi.mock('../src/api/admission.js', () => ({ admissionApi: { enter: vi.fn(), status: vi.fn(), leave: vi.fn() } }));
beforeEach(() => { vi.useFakeTimers(); vi.clearAllMocks(); vi.spyOn(Math,'random').mockReturnValue(0); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
function Site() {
    const location=useLocation();
    return <div>사이트 본문<span data-testid="site-route">{location.pathname+location.search}</span></div>;
}
async function show() { await act(async () => render(<MemoryRouter initialEntries={['/reservations/42?recover=1']}><AdmissionGate><Site /></AdmissionGate></MemoryRouter>)); }
describe('site admission', () => {
    it.each(['ADMITTED', 'DISABLED'])('opens directly without flashing the queue when entry is %s', async state => {
        let resolveEntry;
        admissionApi.enter.mockImplementation(() => new Promise(resolve => { resolveEntry = resolve; }));
        await show();
        expect(screen.queryByText('사이트 본문')).toBeNull();
        expect(screen.queryByRole('heading')).toBeNull();
        expect(screen.queryByRole('region', { name: '사이트 접속 대기 안내' })).toBeNull();
        await act(async () => resolveEntry({ state, pollAfterSeconds: 30 }));
        expect(screen.getByText('사이트 본문')).toBeTruthy();
        expect(screen.queryByRole('region', { name: '사이트 접속 대기 안내' })).toBeNull();
    });
    it('ignores a late expired status after cancellation instead of registering again', async () => {
        admissionApi.enter.mockResolvedValue({state:'WAITING',ahead:2,pollAfterSeconds:5});
        let resolveStatus;
        admissionApi.status.mockImplementation(() => new Promise(resolve => {resolveStatus=resolve;}));
        admissionApi.leave.mockResolvedValue({state:'EXPIRED'});
        await show();
        await act(async () => vi.advanceTimersByTimeAsync(5000));
        await act(async () => fireEvent.click(screen.getByText('대기 취소')));
        await act(async () => resolveStatus({state:'EXPIRED'}));
        await act(async () => vi.advanceTimersByTimeAsync(60000));
        expect(admissionApi.enter).toHaveBeenCalledTimes(1);
        expect(screen.queryByText('사이트 본문')).toBeNull();
        expect(screen.getByText('대기를 취소했어요')).toBeTruthy();
    });
    it('closes an admitted screen on status failure and does not overlap slow polls', async () => {
        admissionApi.enter.mockResolvedValue({state:'ADMITTED',pollAfterSeconds:30});
        let rejectStatus;
        admissionApi.status.mockImplementation(() => new Promise((_resolve,reject) => {rejectStatus=reject;}));
        await show();
        await act(async () => vi.advanceTimersByTimeAsync(120000));
        expect(admissionApi.status).toHaveBeenCalledTimes(1);
        await act(async () => rejectStatus(new Error('Redis unavailable')));
        expect(screen.queryByText('사이트 본문')).toBeNull();
        expect(screen.getByRole('alert').textContent).toContain('Redis unavailable');
        await act(async () => vi.advanceTimersByTimeAsync(10000));
        expect(screen.getByText('사이트 본문')).toBeTruthy();
    });
    it('re-registers an expired token and preserves the original route', async () => {
        admissionApi.enter.mockResolvedValueOnce({state:'EXPIRED'}).mockResolvedValue({state:'ADMITTED',pollAfterSeconds:30});
        await show();
        expect(admissionApi.enter).toHaveBeenCalledTimes(2);
        expect(screen.getByText('사이트 본문')).toBeTruthy();
        expect(screen.getByTestId('site-route').textContent).toBe('/reservations/42?recover=1');
    });
    it('holds the application until entry is granted and shows the real position', async () => {
        admissionApi.enter.mockResolvedValue({state:'WAITING',ahead:1234,pollAfterSeconds:5});
        admissionApi.status.mockResolvedValue({state:'ADMITTED',ahead:0,pollAfterSeconds:30});
        await show();
        expect(screen.queryByText('사이트 본문')).toBeNull();
        expect(screen.getByText('1,234')).toBeTruthy();
        await act(async () => vi.advanceTimersByTimeAsync(5000));
        expect(screen.getByText('사이트 본문')).toBeTruthy();
    });
    it('does not bypass the room on connection failure and recovers automatically', async () => {
        admissionApi.enter.mockRejectedValueOnce(new Error('일시 연결 오류')).mockResolvedValue({state:'ADMITTED',pollAfterSeconds:30});
        await show(); expect(screen.getByRole('alert').textContent).toContain('일시 연결 오류');
        expect(screen.queryByText('사이트 본문')).toBeNull();
        await act(async () => vi.advanceTimersByTimeAsync(10000));
        expect(screen.getByText('사이트 본문')).toBeTruthy();
    });
    it('re-registers when full, and cancels without automatic re-entry', async () => {
        admissionApi.enter.mockResolvedValueOnce({state:'FULL',pollAfterSeconds:15}).mockResolvedValue({state:'WAITING',ahead:0,pollAfterSeconds:5});
        admissionApi.leave.mockResolvedValue({state:'EXPIRED'});
        await show();
        await act(async () => vi.advanceTimersByTimeAsync(15000));
        expect(admissionApi.enter).toHaveBeenCalledTimes(2);
        await act(async () => fireEvent.click(screen.getByText('대기 취소')));
        await act(async () => vi.advanceTimersByTimeAsync(60000));
        expect(admissionApi.enter).toHaveBeenCalledTimes(2);
        expect(screen.getByText('대기를 취소했어요')).toBeTruthy();
    });
    it('returns to the room on expired API admission without replaying the request', async () => {
        admissionApi.enter.mockResolvedValueOnce({state:'ADMITTED',pollAfterSeconds:30}).mockResolvedValue({state:'WAITING',ahead:4,pollAfterSeconds:5});
        await show();
        await act(async () => window.dispatchEvent(new Event('admission-required')));
        expect(screen.queryByText('사이트 본문')).toBeNull();
        expect(screen.getByText('4')).toBeTruthy();
    });
});
