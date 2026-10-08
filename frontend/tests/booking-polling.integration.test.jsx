import { act, cleanup, renderHook } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import useManualHold from '../src/booking/useManualHold.js';
import useSmartCandidates from '../src/booking/useSmartCandidates.js';
import { bookingApi } from '../src/api/booking.js';

const { user } = vi.hoisted(() => ({ user: { id: 1 } }));
vi.mock('../src/hooks/useAuth.js', () => ({ default: () => ({ user }) }));
vi.mock('../src/api/booking.js', () => ({ bookingApi: { group: vi.fn(), payment: vi.fn(), waiting: vi.fn(), smartCandidates: vi.fn() } }));
const group = { id: 7, status: 'ACTIVE' };
const candidates = { candidates: [] };
beforeEach(() => {
    vi.useFakeTimers(); vi.resetAllMocks();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    bookingApi.group.mockResolvedValue(group);
    bookingApi.waiting.mockResolvedValue({ items: [] });
    bookingApi.smartCandidates.mockResolvedValue(candidates);
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
const tick = ms => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
function visibility(value) {
    act(() => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, value });
        document.dispatchEvent(new Event('visibilitychange'));
    });
}
function Wrapper({ children }) { return <MemoryRouter initialEntries={['/?group=7&smart=1']}>{children}</MemoryRouter>; }

function ReservationWrapper({ children }) { return <MemoryRouter initialEntries={['/?group=7&reservation=9']}>{children}</MemoryRouter>; }

test.each(['CONFIRMED', 'CANCELLED', 'EXPIRED'])('manual: %s stops timers but retains focus and manual refresh', async status => {
    bookingApi.payment.mockResolvedValue({ reservation: { id: 9, status } });
    const hook = renderHook(() => useManualHold(), { wrapper: ReservationWrapper });
    await tick(0); await tick(60000);
    expect(bookingApi.payment).toHaveBeenCalledTimes(1);
    visibility('hidden'); visibility('visible'); await tick(0);
    expect(bookingApi.payment).toHaveBeenCalledTimes(2);
    act(() => hook.result.current.refresh()); await tick(0);
    expect(bookingApi.payment).toHaveBeenCalledTimes(3);
    await tick(60000); expect(bookingApi.payment).toHaveBeenCalledTimes(3);
});

test.each(['COMPLETED', 'CANCELLED', 'EXPIRED'])('manual: %s group without reservation stops timers', async status => {
    bookingApi.group.mockResolvedValue({ ...group, status });
    renderHook(() => useManualHold(), { wrapper: Wrapper });
    await tick(60000); expect(bookingApi.group).toHaveBeenCalledTimes(1);
});

test('manual: pending payment continues polling until confirmed', async () => {
    bookingApi.payment.mockResolvedValue({ reservation: { id: 9, status: 'PENDING' } });
    renderHook(() => useManualHold(), { wrapper: ReservationWrapper });
    await tick(2999); expect(bookingApi.payment).toHaveBeenCalledTimes(1);
    bookingApi.payment.mockResolvedValue({ reservation: { id: 9, status: 'CONFIRMED' } });
    await tick(1); expect(bookingApi.payment).toHaveBeenCalledTimes(2);
    await tick(60000); expect(bookingApi.payment).toHaveBeenCalledTimes(2);
});

test.each([
    [5000, 0, 4000], [5000, 0.5, 5000], [5000, 0.99, 5980],
    [20000, 0.99, 10000], [100, 0.5, 3000], [undefined, 0.5, 3000], ['bad', 0.5, 3000],
])('manual: server delay %s and random %s schedule after %s ms', async (nextPollAfterMs, random, expected) => {
    Math.random.mockReturnValue(random);
    bookingApi.waiting.mockResolvedValue({ items: [{ status: 'WAITING' }], nextPollAfterMs });
    renderHook(() => useManualHold(), { wrapper: Wrapper });
    await tick(expected - 1); expect(bookingApi.waiting).toHaveBeenCalledTimes(1);
    await tick(1); expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
});

test('manual: failed focus refresh retries and a new pending hold resumes polling', async () => {
    bookingApi.payment.mockResolvedValue({ reservation: { id: 9, status: 'EXPIRED' } });
    renderHook(() => useManualHold(), { wrapper: ReservationWrapper });
    await tick(0);
    bookingApi.payment.mockRejectedValueOnce(new Error('temporary network failure'));
    act(() => window.dispatchEvent(new Event('focus'))); await tick(0);
    expect(bookingApi.payment).toHaveBeenCalledTimes(2);
    bookingApi.payment.mockResolvedValue({ reservation: { id: 10, status: 'PENDING' } });
    bookingApi.group.mockResolvedValue({ ...group, status: 'HOLDING', activeReservationId: 10 });
    await tick(3000);
    expect(bookingApi.payment).toHaveBeenLastCalledWith(10, expect.any(AbortSignal));
    const calls = bookingApi.payment.mock.calls.length;
    await tick(3000); expect(bookingApi.payment).toHaveBeenCalledTimes(calls + 1);
});

for (const mode of ['manual', 'smart']) {
    const mount = () => renderHook(() => mode === 'manual' ? useManualHold() : useSmartCandidates(user, null, false), { wrapper: Wrapper });
    const api = () => mode === 'manual' ? bookingApi.group : bookingApi.smartCandidates;
    const data = () => mode === 'manual' ? group : candidates;

    test(`${mode}: stays idle when initially hidden and coalesces return/focus events`, async () => {
        visibility('hidden'); mount(); await tick(60000);
        expect(api()).not.toHaveBeenCalled();
        visibility('visible');
        act(() => { window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('focus')); });
        await tick(0); expect(api()).toHaveBeenCalledTimes(1);
        visibility('hidden'); await tick(60000); expect(api()).toHaveBeenCalledTimes(1);
        visibility('visible'); await tick(0); expect(api()).toHaveBeenCalledTimes(2);
    });

    test(`${mode}: slow responses never overlap and focus bursts queue only one refresh`, async () => {
        let finish;
        api().mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
        mount(); await tick(15000);
        act(() => { window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('focus')); });
        expect(api()).toHaveBeenCalledTimes(1);
        await act(async () => finish(data()));
        await tick(0); expect(api()).toHaveBeenCalledTimes(2);
        await tick(2999); expect(api()).toHaveBeenCalledTimes(2);
    });

    test(`${mode}: hidden in-flight reads are aborted, ignored, and refreshed once on return`, async () => {
        let finish;
        api().mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
        const hook = mount();
        const signal = api().mock.calls[0][1];
        visibility('hidden'); expect(signal.aborted).toBe(true);
        visibility('visible');
        act(() => window.dispatchEvent(new Event('focus')));
        expect(api()).toHaveBeenCalledTimes(1);
        await act(async () => finish(mode === 'manual' ? { id: 99 } : { candidates: [{ groupId: 99 }] }));
        if (mode === 'manual') expect(bookingApi.waiting).not.toHaveBeenCalled();
        await tick(0); expect(api()).toHaveBeenCalledTimes(2);
        expect(mode === 'manual' ? hook.result.current.group : hook.result.current.data).toEqual(data());
        await tick(2999); expect(api()).toHaveBeenCalledTimes(2);
    });

    test(`${mode}: unmount aborts reads and removes future refreshes`, async () => {
        let finish;
        api().mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
        const { unmount } = mount(); const signal = api().mock.calls[0][1];
        unmount(); expect(signal.aborted).toBe(true);
        await act(async () => finish(data()));
        act(() => window.dispatchEvent(new Event('focus')));
        await tick(60000); expect(api()).toHaveBeenCalledTimes(1);
    });
}
