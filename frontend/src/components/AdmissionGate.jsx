import { useEffect, useState } from 'react';
import { admissionApi } from '../api/admission.js';
import CommonHeader from './CommonHeader.jsx';
import styles from './AdmissionGate.module.css';

export default function AdmissionGate({ children }) {
    const [status, setStatus] = useState(null);
    const [error, setError] = useState('');
    const [generation, setGeneration] = useState(0);

    useEffect(() => {
        let stopped = false;
        let timer;
        const poll = async (enter = false) => {
            let delay = 10;
            try {
                let next = await (enter ? admissionApi.enter() : admissionApi.status());
                if (stopped) return;
                if (next.state === 'EXPIRED') next = await admissionApi.enter();
                if (stopped) return;
                setStatus(next);
                setError('');
                delay = Math.max(0.5, Math.min(60, next.pollAfterSeconds || 10));
                // A full room must retry registration, not query a token that was never queued.
                enter = next.state === 'FULL';
            } catch (e) {
                if (stopped) return;
                setError(e.message || '접속 안내를 다시 연결하고 있습니다.');
                setStatus(current => ['ADMITTED', 'DISABLED'].includes(current?.state) ? null : current);
                enter = true;
            }
            if (!stopped) timer = window.setTimeout(() => poll(enter), delay * 1000 * (1 + Math.random() * .2));
        };
        poll(true);
        return () => { stopped = true; window.clearTimeout(timer); };
    }, [generation]);

    useEffect(() => {
        const required = () => { setStatus(null); setGeneration(n => n + 1); };
        window.addEventListener('admission-required', required);
        return () => window.removeEventListener('admission-required', required);
    }, []);

    if (!error && ['ADMITTED', 'DISABLED'].includes(status?.state)) return children;
    // Keep the initial admission check invisible without mounting the application's API callers.
    if (!error && !status) return null;
    const waiting = status?.state === 'WAITING';
    const showPosition = waiting && !error;
    return <div className={`app-layout ${styles.page}`}>
        <CommonHeader user={null} disabled />
        <main className={styles.main}>
            <div className={styles.intro}>
                <div className={styles.stateLabel} role={error ? 'alert' : 'status'}><span className={`${styles.stateDot} ${error ? styles.reconnecting : styles.waiting}`} aria-hidden="true" />{error ? '연결 확인 중' : '접속 대기 중'}</div>
            </div>
            <section className={styles.ticket} aria-label="사이트 접속 대기 안내">
                <div className={styles.position} role="status" aria-live="polite" aria-atomic="true">
                    {showPosition ? <><span className={styles.caption}>내 앞 대기 인원</span><div className={styles.number}><span>{Number(status.ahead).toLocaleString('ko-KR')}</span><small>명</small></div></> : <>
                        {error && <svg className={styles.reconnect} viewBox="0 0 48 48" fill="none" aria-hidden="true"><path d="M37 17a15 15 0 0 0-26-3l-3 4m0-10v10h10M11 31a15 15 0 0 0 26 3l3-4m0 10V30H30" /></svg>}
                    </>}
                </div>
            </section>
        </main>
    </div>;
}
