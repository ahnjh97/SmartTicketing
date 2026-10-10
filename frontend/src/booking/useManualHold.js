import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { bookingApi } from '../api/booking.js';
import { openTossPayment } from './tossPayments.js';
import useAuth from '../hooks/useAuth.js';
import { positive } from './state.js';
import { startVisiblePolling } from './visiblePolling.js';

// Persist only request identities. The server remains authoritative for ownership, money and time.
export function requestKey(userId, operation, body) {
    const slot = `booking.request.${userId}.${operation}`;
    const intent = JSON.stringify(body);
    let saved;
    try { saved = JSON.parse(sessionStorage.getItem(slot)); } catch { /* storage is optional */ }
    if (saved?.intent === intent && saved?.key) return { ...saved, slot };
    const value = { intent, key: crypto.randomUUID() };
    try { sessionStorage.setItem(slot, JSON.stringify(value)); } catch { /* still idempotent in this request */ }
    return { ...value, slot };
}
export function forget(request) { try { sessionStorage.removeItem(request.slot); } catch { /* optional */ } }

// Shared hold/payment lifecycle; smart changes only the acquisition endpoint and request intent.
export default function useManualHold({ smart = false } = {}) {
    const { user } = useAuth();
    const [params, setParams] = useSearchParams();
    const groupId = positive(params.get('group')) ? params.get('group') : null;
    const reservationId = positive(params.get('reservation')) ? params.get('reservation') : null;
    const context = new URLSearchParams(params);
    context.delete('seats');
    const identity = `${user?.id}:${context.toString()}`;
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
        let active = true;
        let sequence = 0;
        async function restore(signal) {
            if (gate.current) return;
            const readGeneration = generation.current;
            const requestSequence = ++sequence;
            try {
                const group = groupId ? await bookingApi.group(groupId, signal) : null;
                if (signal.aborted || !active || generation.current !== readGeneration || gate.current) return;
                const id = group?.activeReservationId || reservationId;
                const payment = id ? await bookingApi.payment(id, signal) : null;
                if (signal.aborted || !active || generation.current !== readGeneration || gate.current) return;
                const reservation = payment?.reservation;
                const waiting = !smart && group && !reservation ? await bookingApi.waiting(group.id, signal) : null;
                if (active && !signal.aborted && sequence === requestSequence && !gate.current && generation.current === readGeneration) {
                    // Normalize the recovery URL before exposing payment controls.
                    if (reservation && String(reservation.id) !== reservationId) {
                        setFailure(null);
                        setParams(previous => {
                            const next = new URLSearchParams(previous); next.set('reservation', reservation.id); return next;
                        }, { replace: true });
                        return;
                    }
                    setResult({ identity, group, waiting, reservation, payment, receivedAt: performance.now() });
                    if (reservation) setFailure(null);
                    if (!smart) {
                        if (['CONFIRMED', 'CANCELLED', 'EXPIRED'].includes(reservation?.status)
                            || !reservation && ['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(group?.status)) return null;
                        if (waiting) {
                            const suggested = Number(waiting.nextPollAfterMs);
                            const delay = Number.isFinite(suggested) && suggested > 0 ? Math.max(3000, Math.min(10000, suggested)) : 3000;
                            return Math.min(10000, delay * (0.8 + Math.random() * 0.4));
                        }
                    }
                }
            } catch (error) { if (active && !signal.aborted && sequence === requestSequence && generation.current === readGeneration && !gate.current) setFailure({ identity, error }); }
        }
        const stop = startVisiblePolling(restore, smart ? 10000 : 3000);
        return () => { active = false; stop(); };
    }, [identity, user, groupId, reservationId, revision, setParams, smart]);

    const current = result?.identity === identity ? result : null;
    const error = failure?.identity === identity ? failure.error : null;
    async function hold(body, seatIds, seatZone, waitForSeats = false) {
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
            request = requestKey(user.id, seatZone || waitForSeats ? 'manual-waiting' : smart ? 'smart-hold' : 'hold', seatZone ? { groupId: group.id, seatZone } : smart ? { groupId: group.id }
                : { groupId: group.id, seatIds: [...seatIds].sort((a, b) => a - b) });
            const reservation = seatZone || waitForSeats ? null : smart ? await bookingApi.smartHold(group.id, request.key)
                : await bookingApi.hold(group.id, seatIds, request.key);
            if (seatZone || waitForSeats) await bookingApi.registerWaiting(group.id, [body.selectedShowtimeId], request.key, seatZone, waitForSeats ? seatIds : undefined);
            if (live.current !== identity) return;
            forget(groupRequest); forget(request);
            setParams(previous => { const next = new URLSearchParams(previous); next.set('group', group.id); if (reservation) next.set('reservation', reservation.id); return next; }, { replace: true });
            setRevision(value => value + 1);
        } catch (error) {
            // A network/5xx response may have committed: retain the key for safe retransmission.
            if (request && error.status >= 400 && error.status < 500 && error.status !== 401) forget(request);
            if (live.current === identity) {
                if (attemptedGroup && !groupId) {
                    const next = new URLSearchParams(params); next.set('group', attemptedGroup.id);
                    const failureContext = new URLSearchParams(next); failureContext.delete('seats');
                    setFailure({ identity: `${user.id}:${failureContext}`, error, group: attemptedGroup });
                    setParams(next, { replace: true });
                } else setFailure({ identity, error, group: attemptedGroup });
            }
        } finally { gate.current = false; if (live.current !== null) setBusy(false); }
    }
    async function payToss() {
        const id = current?.reservation?.id;
        if (gate.current) return;
        if (!user || !id) {
            const error = new Error(!user
                ? '로그인 상태를 확인할 수 없습니다. 다시 로그인해주세요.'
                : '예약 정보를 불러오지 못했습니다. 예약 상태를 새로고침해주세요.');
            setFailure({ identity, error });
            return;
        }
        gate.current = true;
        generation.current++;
        setBusy(true);
        setFailure(null);
        try {
            const order = await bookingApi.createTossOrder(id);
            if (live.current !== identity) return;
            await openTossPayment(order, id, user.id);
        } catch (error) {
            if (live.current === identity) setFailure({ identity, error });
        } finally {
            gate.current = false;
            if (live.current === identity) setBusy(false);
        }
    }

    async function mutate(operation, fail = false) {
        const id = operation === 'cancel-waiting' ? current?.group?.id : current?.reservation?.id;
        if (!id || gate.current || !user) return;
        gate.current = true; generation.current++; setBusy(true); setFailure(null);
        const request = requestKey(user.id, operation, { id, fail });
        try {
            if (operation === 'cancel-waiting') {
                await bookingApi.cancelGroup(id, request.key);
                forget(request);
                return;
            }
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
        cancelWaiting: () => mutate('cancel-waiting'), waiting: current?.waiting, payment: current?.payment, pay: fail => mutate('pay', fail), cancel: () => mutate('cancel'),
        receivedAt: current?.receivedAt, loading: Boolean(user && (groupId || reservationId) && !current && !error),
        busy, error, hold, payToss, resetIntent, refresh: () => { setFailure(null); setRevision(value => value + 1); } };
}
