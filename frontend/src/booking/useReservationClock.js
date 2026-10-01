import { useEffect, useState } from 'react';

export function secondsRemaining(reservation, receivedAt, now) {
    if (!reservation?.expiresAt || !reservation?.serverTime || receivedAt == null) return 0;
    return Math.max(0, Math.ceil((Date.parse(reservation.expiresAt) - Date.parse(reservation.serverTime) - (now - receivedAt)) / 1000));
}

export default function useReservationClock(reservation, receivedAt) {
    const [now, setNow] = useState(() => performance.now());
    useEffect(() => {
        const timer = setInterval(() => setNow(performance.now()), 250);
        return () => clearInterval(timer);
    }, []);
    const elapsed = Math.max(0, now - (receivedAt ?? now));
    return { remaining: secondsRemaining(reservation, receivedAt, Math.max(now, receivedAt ?? now)),
        serverNow: Date.parse(reservation?.serverTime) + elapsed };
}
