import { useEffect, useState } from 'react';
import { catalog } from '../api/booking.js';

// Collect the public catalog pages so every branch is available without paging controls.
export default function useTheaterCatalog(enabled, brand) {
    const key = enabled ? brand || 'ALL' : null;
    const [result, setResult] = useState({ key: null, data: null, error: null });
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        if (!key) return;
        const controller = new AbortController();
        let active = true;
        async function load() {
            const items = [];
            for (let page = 0; ; page++) {
                const data = await catalog('theaters', { page, size: 100, ...(brand ? { brand } : {}) }, controller.signal);
                if (!active) return;
                items.push(...data.items);
                if (!data.items.length || items.length >= (data.totalElements ?? items.length)) break;
            }
            if (active) setResult({ key, data: { items }, error: null });
        }
        load().catch(error => { if (active) setResult({ key, data: null, error }); });
        return () => { active = false; controller.abort(); };
    }, [key, brand, revision]);
    const current = key && result.key === key ? result : { data: null, error: null };
    return { ...current, loading: Boolean(key && !current.data && !current.error), retry: () => {
        setResult({ key: null, data: null, error: null });
        setRevision(value => value + 1);
    } };
}
