import { useEffect, useState } from 'react';
import { Navigate, useSearchParams, Link } from 'react-router-dom';
import useAuth from '../hooks/useAuth.js';
import { bookingApi } from '../api/booking.js';
import { positive } from '../booking/state.js';
import GlassButton from '../components/GlassButton.jsx';
import styles from './BookingRestorePage.module.css';

export default function BookingRestorePage() {
    const { user } = useAuth();
    const [params] = useSearchParams();
    const id = params.get('group');
    const identity = `${user?.id}:${id}`;
    const [result, setResult] = useState(null);
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        if (!user || !positive(id)) return;
        const controller = new AbortController();
        let active = true;
        bookingApi.recovery(id, controller.signal).then(
            data => { if (active) setResult({ identity, data }); },
            error => { if (active) setResult({ identity, error }); },
        );
        return () => { active = false; controller.abort(); };
    }, [identity, user, id, revision]);
    const current = result?.identity === identity ? result : null;
    if (current?.data) return <Navigate to={current.data.path} replace/>;
    return <section className={styles.page}><div className={styles.panel}><h1>예약 및 대기 복구</h1>
        {!positive(id) ? <p role="alert">올바른 예매 링크가 아닙니다.</p> : current?.error ? <><p role="alert">{current.error.message}</p>
            <GlassButton onClick={() => { setResult(null); setRevision(n => n + 1); }}>다시 확인</GlassButton></> : null}
        <Link to="/bookings">내 대기 및 선점</Link>
    </div></section>;
}
