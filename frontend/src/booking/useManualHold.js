import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { bookingApi } from '../api/booking.js';
import useAuth from '../hooks/useAuth.js';
import { positive } from './state.js';

// Persist only request identities. The server remains authoritative for ownership, money and time.
function requestKey(userId, operation, body) {
    const slot = `booking.request.${userId}.${operation}`;
    const intent = JSON.stringify(body);
    let saved;
    try { saved = JSON.parse(sessionStorage.getItem(slot)); } catch { /* storage is optional */ }
    if (saved?.intent === intent && saved?.key) return { ...saved, slot };
    const value = { intent, key: crypto.randomUUID() };
    try { sessionStorage.setItem(slot, JSON.stringify(value)); } catch { /* still idempotent in this request */ }
    return { ...value, slot };
}
function forget(request) { try { sessionStorage.removeItem(request.slot); } catch { /* optional */ } }

// Shared hold/payment lifecycle; smart changes only the acquisition endpoint and request intent.
export default function useManualHold({ smart = false } = {}) {
    const { user } = useAuth();
    const [params, setParams] = useSearchParams();
    const groupId = positive(params.get('group')) ? params.get('group') : null;
    const reservationId = positive(params.get('reservation')) ? params.get('reservation') : null;
    const identity = `${user?.id}:${params.toString()}`;
    const [result, setResult] = useState(null);
    const [failure, setFailure] = useState(null);
    const [busy, setBusy] = useState(false);
    const [revision, setRevision] = useState(0);
    const gate = useRef(false);
    const generation = useRef(0);
    const live = useRef(null);
    useEffect(() => { live.current = identity; return () => { live.current = null; }; }, [identity]);
    useEffect(() => {
        if (!user || (!groupId && !reservationId)) return;
        const controller = new AbortController();
        let active = true;
        let sequence = 0;
        async function restore() {
            if (gate.current) return;
            const readGeneration = generation.current;
            const requestSequence = ++sequence;
            try {
                const group = groupId ? await bookingApi.group(groupId, controller.signal) : null;
                const id = reservationId || group?.activeReservationId;
                const payment = id ? await bookingApi.payment(id, controller.signal) : null;
                const reservation = payment?.reservation;
                if (active && sequence === requestSequence && !gate.current && generation.current === readGeneration) {
                    // Normalize the recovery URL before exposing payment controls.
                    if (reservation && !reservationId) {
                        setFailure(null);
                        setParams(previous => {
                            const next = new URLSearchParams(previous); next.set('reservation', reservation.id); return next;
                        }, { replace: true });
                        return;
                    }
                    setResult({ identity, group, reservation, payment, receivedAt: performance.now() });
                    if (reservation) setFailure(null);
                }
            } catch (error) { if (active && sequence === requestSequence) setFailure({ identity, error }); }
        }
        restore();
        const timer = setInterval(restore, 10000);
        const refresh = () => { if (document.visibilityState !== 'hidden') restore(); };
        window.addEventListener('focus', refresh);
        document.addEventListener('visibilitychange', refresh);
        return () => { active = false; clearInterval(timer); controller.abort(); window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
    }, [identity, user, groupId, reservationId, revision, setParams]);

    const current = result?.identity === identity ? result : null;
    const error = failure?.identity === identity ? failure.error : null;
    async function hold(body, seatIds) {
        if (gate.current || !user) return;
        gate.current = true; generation.current++; setBusy(true); setFailure(null);
        let request;
        let attemptedGroup;
        try {
            request = requestKey(user.id, 'group', body);
            const group = current?.group || (failure?.identity === identity ? failure.group : null)
                || await bookingApi.createGroup(body, request.key);
            attemptedGroup = group;
            if (live.current !== identity) return;
            // Keep the creation key until the hold is resolved, including interrupted responses.
            const groupRequest = request;
            request = requestKey(user.id, smart ? 'smart-hold' : 'hold', smart ? { groupId: group.id }
                : { groupId: group.id, seatIds: [...seatIds].sort((a, b) => a - b) });
            const reservation = smart ? await bookingApi.smartHold(group.id, request.key)
                : await bookingApi.hold(group.id, seatIds, request.key);
            if (live.current !== identity) return;
            forget(groupRequest); forget(request);
            setParams(previous => { const next = new URLSearchParams(previous); next.set('group', group.id); next.set('reservation', reservation.id); return next; }, { replace: true });
        } catch (error) {
            // A network/5xx response may have committed: retain the key for safe retransmission.
            if (request && error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) {
                if (smart && attemptedGroup && !groupId) {
                    const next = new URLSearchParams(params); next.set('group', attemptedGroup.id);
                    setFailure({ identity: `${user.id}:${next}`, error, group: attemptedGroup });
                    setParams(next, { replace: true });
                } else setFailure({ identity, error, group: attemptedGroup });
            }
        } finally { gate.current = false; if (live.current !== null) setBusy(false); }
    }
    async function mutate(operation, fail = false) {
        const id = current?.reservation?.id;
        if (!id || gate.current || !user) return;
        gate.current = true; generation.current++; setBusy(true); setFailure(null);
        const request = requestKey(user.id, operation, { id, fail });
        try {
            if (operation === 'pay') await bookingApi.pay(id, request.key, fail); else await bookingApi.cancel(id, request.key);
            forget(request);
            // A replay contains its original serverTime/state. Verify current state before success feedback.
            const latest = await bookingApi.payment(id);
            if (live.current === identity) setResult({ ...current, reservation: latest.reservation,
                payment: latest, receivedAt: performance.now() });
        } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) setFailure({ identity, error });
        } finally {
            gate.current = false;
            if (live.current === identity) { setBusy(false); setRevision(value => value + 1); }
        }
    }
    function resetIntent() {
        for (const operation of ['group', 'smart-hold']) {
            try { sessionStorage.removeItem(`booking.request.${user?.id}.${operation}`); } catch { /* optional */ }
        }
        setFailure(null);
    }
    return { user, groupId, reservationId, group: current?.group || (failure?.identity === identity ? failure.group : null), reservation: current?.reservation,
        payment: current?.payment, pay: fail => mutate('pay', fail), cancel: () => mutate('cancel'),
        receivedAt: current?.receivedAt, loading: Boolean(user && (groupId || reservationId) && !current && !error),
        busy, error, hold, resetIntent, refresh: () => { setFailure(null); setRevision(value => value + 1); } };
}
