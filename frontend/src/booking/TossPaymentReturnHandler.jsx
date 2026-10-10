import { useEffect, useRef } from 'react';
import { bookingApi } from '../api/booking.js';

export default function TossPaymentReturnHandler() {
    const handled = useRef(false);

    useEffect(() => {
        if (handled.current) return;
        const current = new URL(window.location.href);
        const params = current.searchParams;
        const reservationId = params.get('tossReservationId');
        if (!reservationId) return;
        handled.current = true;

        const returnToBooking = result => {
            const target = new URL(window.location.pathname, window.location.origin);
            const savedRoute = params.get('tossReturnTo') || '#/theaters';
            const route = savedRoute.startsWith('#') ? savedRoute.slice(1) : savedRoute;
            const queryIndex = route.indexOf('?');
            const routePath = queryIndex >= 0 ? route.slice(0, queryIndex) : route;
            const routeParams = new URLSearchParams(queryIndex >= 0 ? route.slice(queryIndex + 1) : '');
            routeParams.set('reservation', reservationId);
            routeParams.set('tossResult', result);
            target.hash = `${routePath || '/theaters'}?${routeParams.toString()}`;
            window.location.replace(target.toString());
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
    }, []);

    return null;
}
