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
            target.hash = `/theaters?reservation=${encodeURIComponent(reservationId)}&tossResult=${encodeURIComponent(result)}`;
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

        bookingApi.confirmToss(reservationId, {
            paymentKey,
            orderId,
            amount,
        }, crypto.randomUUID()).then(() => {
            returnToBooking('success');
        }).catch(() => {
            returnToBooking('error');
        });
    }, []);

    return null;
}
