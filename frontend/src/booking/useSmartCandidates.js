import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { bookingApi } from '../api/booking.js';
import { requestKey, forget } from './useManualHold.js';

export default function useSmartCandidates(user, body, ready) {
    const [params, setParams] = useSearchParams();
    const managing = params.get('smart') === '1' || Boolean(params.get('group') || params.get('candidate'));
    const selectedId = params.get('candidate') || params.get('group');
    const candidateScope = params.get('candidates');
    const candidateIds = candidateScope ? candidateScope.split(',').filter(id => /^[1-9]\d*$/.test(id)) : null;
    const identity = `${user?.id}:${managing ? `manage:${candidateScope || 'all'}` : params.toString()}`;
    const [result, setResult] = useState(null);
    const [failure, setFailure] = useState(null);
    const [busy, setBusy] = useState(false);
    const [revision, setRevision] = useState(0);
    const live = useRef(null), gate = useRef(false), started = useRef(null), epoch = useRef(0);
    const initialRead = useRef(null);
    useEffect(() => { live.current = identity; return () => { live.current = null; }; }, [identity]);
    const save = data => setResult({ identity, data, receivedAt: performance.now() });
    const current = result?.identity === identity ? result : null;
    const data = current?.data;
    const error = failure?.identity === identity ? failure.error : null;

    const failed = Boolean(error);

    async function create() {
        if (!user || !ready || gate.current || managing) return;
        gate.current = true; setBusy(true); setFailure(null);
        const request = requestKey(user.id, 'smart-candidates', body);
        try {
            const created = await bookingApi.createSmartCandidates(body, request.key);
            if (live.current !== identity) return;
            if (created.initial?.candidates) {
                const nextIdentity = `${user.id}:manage:${created.groupIds.join(',')}`;
                initialRead.current = nextIdentity;
                setResult({ identity: nextIdentity, data: created.initial, receivedAt: performance.now() });
            }
            setParams(previous => { const next = new URLSearchParams(previous); next.set('smart', '1'); next.set('candidates', created.groupIds.join(',')); next.delete('candidate'); next.delete('plan'); next.delete('group'); next.delete('reservation'); return next; }, { replace: true });
            forget(request);
        } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) setFailure({ identity, error });
        } finally { gate.current = false; if (live.current !== null) { setBusy(false); setRevision(n => n + 1); } }
    }
    useEffect(() => {
        if (!managing && user && ready && !error && started.current !== identity) {
            started.current = identity; create();
        }
    });
    useEffect(() => {
        if (!user || (!managing && !failed)) return;
        let active = true, sequence = 0, running = false, again = false, timer;
        const controller = new AbortController();
        async function read() {
            if (!active) return;
            if (running) { again=true; return; }
            clearTimeout(timer);
            if (gate.current) { timer=setTimeout(read,3000); return; }
            running=true;
            const request = ++sequence, generation = epoch.current;
            try {
                const data = await bookingApi.smartCandidates(selectedId, controller.signal);
                if (active && request === sequence && generation === epoch.current && !gate.current) {
                    setResult({ identity, data, receivedAt: performance.now() });
                }
            } catch (error) { if (active && request === sequence && generation === epoch.current) setFailure({ identity, error }); }
            finally { running=false; if(active)timer=setTimeout(read,again?0:3000); again=false; }
        }
        if(initialRead.current===identity) { initialRead.current=null; timer=setTimeout(read,3000); }
        else read();
        const focus = () => { if (document.visibilityState !== 'hidden') read(); };
        window.addEventListener('focus', focus); document.addEventListener('visibilitychange', focus);
        return () => { active = false; controller.abort(); clearTimeout(timer); window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', focus); };
    }, [identity, managing, selectedId, user, revision, failed]);

    async function mutate(candidate, operation, fail = false) {
        if (gate.current || !user || !current) return;
        const reservationId = candidate.payment?.reservation?.id;
        if (operation !== 'cancel-waiting' && !reservationId) return;
        gate.current = true; epoch.current++; setBusy(true); setFailure(null);
        const request = requestKey(user.id, `smart-${candidate.groupId}-${operation}`, { reservationId, groupId: candidate.groupId, fail });
        try {
            if (operation === 'pay') await bookingApi.pay(reservationId, request.key, fail);
            else if (operation === 'cancel') await bookingApi.cancel(reservationId, request.key);
            else await bookingApi.cancelGroup(candidate.groupId, request.key);
            forget(request);
            const data = await bookingApi.smartCandidates(candidate.groupId);
            if (live.current === identity) setParams(previous => { const next = new URLSearchParams(previous); next.set('candidate', candidate.groupId); return next; }, { replace: true });
            if (live.current === identity) save(data);
        } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) setFailure({ identity, error });
        } finally { gate.current = false; if (live.current === identity) { setBusy(false); setRevision(n => n + 1); } }
    }
    return { data, receivedAt: current?.receivedAt, error, busy, create, mutate,
        refresh: () => { setFailure(null); setRevision(n => n + 1); },
        select: id => setParams(previous => { const next = new URLSearchParams(previous); next.set('candidate', id); return next; }, { replace: true }),
        selectedId, managing, candidateIds };
}
