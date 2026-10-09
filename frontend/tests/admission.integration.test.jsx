import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import AdmissionGate from '../src/components/AdmissionGate.jsx';
import { admissionApi } from '../src/api/admission.js';
import { MemoryRouter } from 'react-router-dom';
vi.mock('../src/api/admission.js', () => ({ admissionApi: { enter: vi.fn(), status: vi.fn(), leave: vi.fn() } }));
beforeEach(() => { vi.useFakeTimers(); vi.clearAllMocks(); vi.spyOn(Math,'random').mockReturnValue(0); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
async function show() { await act(async () => render(<MemoryRouter><AdmissionGate><div>사이트 본문</div></AdmissionGate></MemoryRouter>)); }
describe('site admission', () => {
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
