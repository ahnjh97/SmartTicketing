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
    useEffect(() => { live.current = identity; return () => { live.current = null; }; }, [identity]);
    useEffect(() => {
        if (!userId || !groupId) return;
        const controller = new AbortController();
        let active = true, sequence = 0;
        async function read() {
            if (gate.current) return;
            const generation = epoch.current, request = ++sequence;
            try {
                const data = await bookingApi.waiting(groupId, controller.signal);
                if (active && request === sequence && generation === epoch.current && !gate.current)
                    setResult({ identity, data });
            } catch (error) { if (active && request === sequence && generation === epoch.current) setFailure({ identity, error }); }
        }
        read();
        const timer = setInterval(read, 3000);
        const focus = () => { if (document.visibilityState !== 'hidden') read(); };
        window.addEventListener('focus', focus); document.addEventListener('visibilitychange', focus);
        return () => { active = false; controller.abort(); clearInterval(timer); window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', focus); };
    }, [identity, userId, groupId, revision]);

    async function mutate(operation, showtimeIds = []) {
        if (gate.current || !userId || !groupId) return;
        gate.current = true; epoch.current++; setBusy(true); setFailure(null);
        const ids = [...showtimeIds].sort((a,b) => a-b);
        const request = requestKey(userId, operation, { groupId, showtimeIds: ids });
        try {
            if (operation === 'waiting') await bookingApi.registerWaiting(groupId, ids, request.key);
            else await bookingApi.cancelGroup(groupId, request.key);
            forget(request);
            const data = await bookingApi.waiting(groupId);
            if (live.current === identity) setResult({ identity, data });
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
