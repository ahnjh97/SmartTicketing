import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { bookingApi } from '../api/booking.js';
import { requestKey, forget } from './useManualHold.js';
import { startVisiblePolling } from './visiblePolling.js';
import { openTossPayment } from './tossPayments.js';

export default function useSmartCandidates(user, body, ready) {
    const [params, setParams] = useSearchParams();
    const managing = params.get('smart') === '1' || Boolean(params.get('group') || params.get('candidate'));
    const candidateScope = params.get('candidates');
    const candidateIds = candidateScope ? candidateScope.split(',').filter(id => /^[1-9]\d*$/.test(id)) : null;
    // On a payment return, older saved routes may contain candidates=... but no candidate=...
    // Use the first scoped group so the detail API can reload its reservation state.
    const selectedId = params.get('candidate') || params.get('group') || candidateIds?.[0];
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
        let active = true, sequence = 0;
        async function read(signal) {
            if (!active || gate.current) return;
            const request = ++sequence, generation = epoch.current;
            try {
                const data = await bookingApi.smartCandidates(selectedId, signal);
                if (active && !signal.aborted && request === sequence && generation === epoch.current && !gate.current) {
                    setResult({ identity, data, receivedAt: performance.now() });
                }
            } catch (error) { if (active && !signal.aborted && request === sequence && generation === epoch.current && !gate.current) setFailure({ identity, error }); }
        }
        const initialDelay = initialRead.current===identity ? 3000 : 0;
        initialRead.current=null;
        const stop = startVisiblePolling(read,3000,initialDelay);
        return () => { active = false; stop(); };
    }, [identity, managing, selectedId, user, revision, failed]);

    async function payToss(candidate) {
        const reservationId = candidate.payment?.reservation?.id;
        if (gate.current || !user || !current || !reservationId) {
            if (!user || !reservationId) {
                const message = !user
                    ? '로그인 상태를 확인할 수 없습니다. 다시 로그인해주세요.'
                    : '예약 정보를 불러오지 못했습니다. 예약 상태를 새로고침해주세요.';
                setFailure({ identity, error: new Error(message) });
            }
            return;
        }
        gate.current = true;
        epoch.current++;
        setBusy(true);
        setFailure(null);
        try {
            const order = await bookingApi.createTossOrder(reservationId);
            if (live.current !== identity) return;
            await openTossPayment(order, reservationId, user.id, candidate.groupId);
        } catch (error) {
            if (live.current === identity) setFailure({ identity, error });
        } finally {
            gate.current = false;
            if (live.current === identity) { setBusy(false); setRevision(n => n + 1); }
        }
    }

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
            if (live.current === identity) setParams(previous => {
                const next = new URLSearchParams(previous);
                next.set('candidate', candidate.groupId);
                next.delete('tossResult');
                return next;
            }, { replace: true });
            if (live.current === identity) save(data);
        } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) setFailure({ identity, error });
        } finally { gate.current = false; if (live.current === identity) { setBusy(false); setRevision(n => n + 1); } }
    }
    return { data, receivedAt: current?.receivedAt, error, busy, create, mutate, payToss,
        refresh: () => { setFailure(null); setRevision(n => n + 1); },
        select: id => setParams(previous => {
            const next = new URLSearchParams(previous);
            next.set('candidate', id);
            // Payment-return parameters belong only to the reservation just paid.
            next.delete('reservation');
            next.delete('tossResult');
            return next;
        }, { replace: true }),
        selectedId, managing, candidateIds };
}
