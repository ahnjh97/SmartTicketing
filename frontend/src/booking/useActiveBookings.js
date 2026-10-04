import { useEffect, useState } from 'react';
import { bookingApi } from '../api/booking.js';

export default function useActiveBookings(userId) {
    const [state, setState] = useState(null);
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        if (!userId) return;
        const controller = new AbortController();
        let active = true;
        let inFlight = false;
        async function refresh() {
            if (inFlight) return;
            inFlight = true;
            setState(previous => ({ ...(previous?.userId === userId ? previous : {}), userId, refreshing: true }));
            try {
                const data = await bookingApi.active(controller.signal);
                const receivedAt = performance.now();
                const items = data.map(item => ({ ...item, receivedAt }));
                if (active) setState({ userId, items, refreshing: false });
            } catch (error) {
                if (active) setState(previous => ({ ...previous, userId, error, refreshing: false }));
            } finally { inFlight = false; }
        }
        const visibleRefresh = () => { if (document.visibilityState !== 'hidden') refresh(); };
        refresh();
        const timer = window.setInterval(visibleRefresh, 10000);
        window.addEventListener('focus', visibleRefresh);
        document.addEventListener('visibilitychange', visibleRefresh);
        return () => {
            active = false;
            controller.abort();
            window.clearInterval(timer);
            window.removeEventListener('focus', visibleRefresh);
            document.removeEventListener('visibilitychange', visibleRefresh);
        };
    }, [userId, revision]);
    return { ...(state?.userId === userId ? state : {}), retry: () => setRevision(value => value + 1) };
}
