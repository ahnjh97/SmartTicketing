import { useEffect, useRef, useState } from 'react';
import { bookingApi } from '../api/booking.js';
import { requestKey, forget } from './useManualHold.js';

export default function useWaitingQueues(userId, groupId) {
    const identity = `${userId}:${groupId}`;
    const [result, setResult] = useState(null);
    const [failure, setFailure] = useState(null);
    const [busy, setBusy] = useState(false);
    const [revision, setRevision] = useState(0);
    const live = useRef(null);
    const gate = useRef(false);
    const epoch = useRef(0);
    const pollingRequest = useRef(null);
    const refreshed = useRef(null);
    useEffect(() => { live.current = identity; return () => { live.current = null; }; }, [identity]);
    useEffect(() => {
        if (!userId || !groupId) return;
        let active = true, running = false, again = false, timer;
        let delay = refreshed.current?.identity === identity ? refreshed.current.delay : 3000;
        const visible = () => document.visibilityState !== 'hidden';
        const schedule = () => {
            clearTimeout(timer);
            if (active && visible()) timer = setTimeout(read, Math.min(10000, delay * (0.8 + Math.random() * 0.4)));
        };
        async function read() {
            if (!active || !visible()) return;
            if (running) { again = true; return; }
            clearTimeout(timer);
            if (gate.current) { schedule(); return; }
            running = true;
            const controller = new AbortController();
            pollingRequest.current = controller;
            const generation = epoch.current;
            try {
                const data = await bookingApi.waiting(groupId, controller.signal);
                if (active && generation === epoch.current && !gate.current) {
                    setResult({ identity, data });
                    setFailure(null);
                    delay = pollDelay(data);
                }
            } catch (error) {
                if (active && generation === epoch.current && error.name !== 'AbortError') setFailure({ identity, error });
            } finally {
                running = false;
                if (pollingRequest.current === controller) pollingRequest.current = null;
                if (again && active && visible() && !gate.current) { again = false; void read(); }
                else { again = false; schedule(); }
            }
        }
        if (refreshed.current?.identity === identity) { refreshed.current = null; schedule(); }
        else void read();
        const focus = () => { clearTimeout(timer); if (visible()) void read(); };
        window.addEventListener('focus', focus); document.addEventListener('visibilitychange', focus);
        return () => { active = false; pollingRequest.current?.abort(); clearTimeout(timer); window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', focus); };
    }, [identity, userId, groupId, revision]);

    async function mutate(operation, showtimeIds = []) {
        if (gate.current || !userId || !groupId) return;
        gate.current = true; epoch.current++; setBusy(true); setFailure(null);
        pollingRequest.current?.abort();
        const ids = [...showtimeIds].sort((a,b) => a-b);
        const request = requestKey(userId, operation, { groupId, showtimeIds: ids });
        try {
            if (operation === 'waiting') await bookingApi.registerWaiting(groupId, ids, request.key);
            else await bookingApi.cancelGroup(groupId, request.key);
            forget(request);
            const data = await bookingApi.waiting(groupId);
            if (live.current === identity) {
                setResult({ identity, data });
                refreshed.current = { identity, delay: pollDelay(data) };
            }
        } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) setFailure({ identity, error });
        } finally {
            gate.current = false;
            if (live.current === identity) { setBusy(false); setRevision(value => value+1); }
        }
    }
    return { data: result?.identity === identity ? result.data : null, error: failure?.identity === identity ? failure.error : null,
        busy, register: ids => mutate('waiting', ids), cancel: () => mutate('cancel-group'),
        retry: () => { setFailure(null); setRevision(value => value+1); } };
}

function pollDelay(data) {
    const value = Number(data?.nextPollAfterMs);
    return Number.isFinite(value) && value > 0 ? Math.max(3000, Math.min(10000, value)) : 3000;
}
