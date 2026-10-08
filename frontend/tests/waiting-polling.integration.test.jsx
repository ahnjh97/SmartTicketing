import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import useWaitingQueues from '../src/booking/useWaitingQueues.js';
import { bookingApi } from '../src/api/booking.js';

vi.mock('../src/api/booking.js', () => ({ bookingApi: { waiting: vi.fn(), registerWaiting: vi.fn(), cancelGroup: vi.fn() } }));
const data = { groupId: 7, groupStatus: 'ACTIVE', items: [], choices: [], nextPollAfterMs: 5000 };
beforeEach(() => {
    vi.useFakeTimers(); vi.clearAllMocks(); sessionStorage.clear();
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    bookingApi.waiting.mockResolvedValue(data);
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
const tick = ms => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
const visibility = value => act(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value });
    document.dispatchEvent(new Event('visibilitychange'));
});

test('waits for the previous response and then follows the server interval', async () => {
    let resolve;
    bookingApi.waiting.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    renderHook(() => useWaitingQueues(1, 7));
    await tick(20000);
    expect(bookingApi.waiting).toHaveBeenCalledTimes(1);
    await act(async () => resolve(data));
    await tick(4999); expect(bookingApi.waiting).toHaveBeenCalledTimes(1);
    await tick(1); expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
});

test('pauses while hidden and refreshes on return without overlapping focus requests', async () => {
    let resolve;
    renderHook(() => useWaitingQueues(1, 7)); await tick(0);
    visibility('hidden'); await tick(60000);
    expect(bookingApi.waiting).toHaveBeenCalledTimes(1);
    bookingApi.waiting.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    visibility('visible');
    act(() => { window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('focus')); });
    expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
    await act(async () => resolve(data));
    expect(bookingApi.waiting).toHaveBeenCalledTimes(3);
    await tick(4999); expect(bookingApi.waiting).toHaveBeenCalledTimes(3);
});

test('aborts stale polling during a command and refreshes only once after success', async () => {
    let resolve;
    bookingApi.waiting.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    bookingApi.registerWaiting.mockResolvedValue({});
    const { result } = renderHook(() => useWaitingQueues(1, 7));
    const signal = bookingApi.waiting.mock.calls[0][1];
    await act(async () => result.current.register([1]));
    expect(signal.aborted).toBe(true);
    expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
    await act(async () => resolve({ ...data, groupStatus: 'CANCELLED' }));
    expect(result.current.data.groupStatus).toBe('ACTIVE');
    await tick(4999); expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
    await tick(1); expect(bookingApi.waiting).toHaveBeenCalledTimes(3);
});

test('recovers from errors and cancels polling on unmount', async () => {
    bookingApi.waiting.mockRejectedValueOnce(new Error('temporary'));
    const { result, unmount } = renderHook(() => useWaitingQueues(1, 7)); await tick(0);
    expect(result.current.error.message).toBe('temporary');
    await tick(3000); expect(result.current.error).toBe(null);
    unmount(); await tick(30000); expect(bookingApi.waiting).toHaveBeenCalledTimes(2);
});
