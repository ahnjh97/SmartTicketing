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

        const confirmation = { paymentKey, orderId, amount };
        bookingApi.confirmToss(reservationId, confirmation, crypto.randomUUID()).catch(error => {
            // Recover once if the provider may have approved but the local response was lost.
            if (error.status === 0 || error.status >= 500)
                return bookingApi.confirmToss(reservationId, confirmation, crypto.randomUUID());
            throw error;
        }).then(() => {
            returnToBooking('success');
        }).catch(() => {
            returnToBooking('error');
        });
    }, []);

    return null;
}
