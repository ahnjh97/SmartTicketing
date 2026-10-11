import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { bookingApi } from '../api/booking.js';
import { bookingSelectionPath } from './navigation.js';

export default function TossPaymentReturnHandler() {
    const handled = useRef(false);
    const navigate = useNavigate();

    useEffect(() => {
        if (handled.current) return;
        const current = new URL(window.location.href);
        const params = current.searchParams;
        const reservationId = params.get('tossReservationId');
        if (!reservationId) return;
        handled.current = true;
        const returnSlot = `toss.return-to.${reservationId}`;
        let savedRoute = params.get('tossReturnTo');
        if (!savedRoute) {
            try { savedRoute = sessionStorage.getItem(returnSlot); } catch { /* use safe fallback below */ }
        }
        savedRoute ||= '#/theaters';

        const returnToBooking = result => {
            const route = savedRoute.startsWith('#') ? savedRoute.slice(1) : savedRoute;
            try { sessionStorage.removeItem(returnSlot); } catch { /* optional browser storage */ }
            const queryIndex = route.indexOf('?');
            const requestedPath = queryIndex >= 0 ? route.slice(0, queryIndex) : route;
            const routePath = ['/movies', '/theaters'].includes(requestedPath) ? requestedPath : '/theaters';
            const routeParams = new URLSearchParams(queryIndex >= 0 ? route.slice(queryIndex + 1) : '');
            // Restore the first candidate as the active candidate if the saved smart-booking
            // URL only contains the candidate scope. The detail panel needs a selected group.
            if (!routeParams.has('candidate') && !routeParams.has('group')) {
                const firstCandidate = routeParams.get('candidates')?.split(',').find(id => /^[1-9]\d*$/.test(id));
                if (firstCandidate) routeParams.set('candidate', firstCandidate);
            }
            routeParams.set('reservation', reservationId);
            routeParams.set('tossResult', result);
            // Remove the provider callback before rendering booking routes. Replace this
            // history entry with passive conditions, then push the result on top of it.
            const cleanUrl = new URL(window.location.href);
            cleanUrl.search = '';
            window.history.replaceState(window.history.state, '', cleanUrl);
            navigate(bookingSelectionPath(routePath, routeParams), { replace: true });
            navigate(`${routePath}?${routeParams.toString()}`);
        };

        if (params.has('tossFailed') || params.has('code')) {
            returnToBooking('failed');
            return;
        }

        const paymentKey = params.get('paymentKey');
        const orderId = params.get('orderId');
        const amount = Number(params.get('amount'));
        if (!paymentKey || !orderId || !Number.isSafeInteger(amount) || amount <= 0) {
            returnToBooking('invalid');
            return;
        }

        const confirmation = { paymentKey, orderId, amount };
        const idempotencyKey = crypto.randomUUID();
        bookingApi.confirmToss(reservationId, confirmation, idempotencyKey).catch(error => {
            // Recover once if the provider may have approved but the local response was lost.
            if (error.status === 0 || error.status >= 500)
                return bookingApi.confirmToss(reservationId, confirmation, idempotencyKey);
            throw error;
        }).then(() => {
            returnToBooking('success');
        }).catch(() => {
            returnToBooking('error');
        });
    }, [navigate]);

    return null;
}
