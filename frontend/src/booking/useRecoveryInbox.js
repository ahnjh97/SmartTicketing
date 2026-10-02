import { useEffect, useState } from 'react';
import { bookingApi } from '../api/booking.js';
import { notificationApi } from '../api/notifications.js';

export default function useRecoveryInbox(userId, before) {
    const [result, setResult] = useState(null);
    const [revision, setRevision] = useState(0);
    const identity = `${userId}:${before || ''}`;
    useEffect(() => {
        if (!userId) return;
        const controller = new AbortController();
        let active = true;
        let sequence = 0;
        async function refresh() {
            const current = ++sequence;
            const [groups, notifications] = await Promise.allSettled([
                bookingApi.mine(before, controller.signal), notificationApi.list(false, controller.signal),
            ]);
            if (active && current === sequence) setResult({ identity, groups, notifications });
        }
        refresh();
        const focus = () => { if (document.visibilityState !== 'hidden') refresh(); };
        const timer = setInterval(focus, 10000);
        window.addEventListener('focus', focus);
        document.addEventListener('visibilitychange', focus);
        return () => { active = false; controller.abort(); clearInterval(timer); window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', focus); };
    }, [identity, userId, before, revision]);
    return { result: result?.identity === identity ? result : null, retry: () => setRevision(n => n + 1) };
}
