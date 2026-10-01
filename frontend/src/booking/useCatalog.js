import { useEffect, useState } from 'react';
import { catalog } from '../api/booking.js';

export default function useCatalog(path, query = {}, refreshKey = '') {
    const serialized = JSON.stringify(query);
    const key = path ? path + serialized + refreshKey : null;
    const [result, setResult] = useState({ key: null, data: null, error: null });
    const [revision, retry] = useState(0);
    useEffect(() => {
        if (!path) return;
        const controller = new AbortController();
        let active = true;
        catalog(path, JSON.parse(serialized), controller.signal).then(
            data => { if (active) setResult({ key, data, error: null }); },
            error => { if (active) setResult({ key, data: null, error }); },
        );
        return () => { active = false; controller.abort(); };
    }, [path, serialized, key, revision]);
    const current = result.key === key ? result : { data: null, error: null };
    return { ...current, loading: Boolean(path && !current.data && !current.error), retry: () => {
        setResult({ key: null, data: null, error: null }); retry(n => n + 1);
    } };
}
