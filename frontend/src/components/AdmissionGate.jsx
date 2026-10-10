import { useEffect, useState } from 'react';
import { admissionApi } from '../api/admission.js';
import CommonHeader from './CommonHeader.jsx';
import styles from './AdmissionGate.module.css';

export default function AdmissionGate({ children }) {
    const [status, setStatus] = useState(null);
    const [error, setError] = useState('');
    const [cancelled, setCancelled] = useState(false);
    const [leaving, setLeaving] = useState(false);
    const [generation, setGeneration] = useState(0);

    useEffect(() => {
        if (cancelled) return;
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
                delay = Math.max(3, Math.min(60, next.pollAfterSeconds || 10));
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
    }, [cancelled, generation]);

    useEffect(() => {
        const required = () => { setStatus(null); setGeneration(n => n + 1); };
        window.addEventListener('admission-required', required);
        return () => window.removeEventListener('admission-required', required);
    }, []);

    async function leave() {
        setLeaving(true);
        // Stop polling before releasing the place so an in-flight check cannot re-enter.
        setCancelled(true);
        try { await admissionApi.leave(); setError(''); setStatus(null); }
        catch (e) { setError(e.message); }
        finally { setLeaving(false); }
    }
    if (!cancelled && !error && ['ADMITTED', 'DISABLED'].includes(status?.state)) return children;
    // Keep the initial admission check invisible without mounting the application's API callers.
    if (!cancelled && !error && !status) return null;
    const waiting = status?.state === 'WAITING';
    return <div className={`app-layout ${styles.page}`}>
        <CommonHeader user={null} disabled />
        <main className={styles.main}>
            <div className={styles.intro}>
                <h1>{cancelled ? '대기를 취소했어요' : waiting ? '잠시만 기다려 주세요' : status?.state === 'FULL' ? '지금 접속이 많이 몰리고 있어요' : error ? '접속 안내를 다시 연결하고 있어요' : '입장 가능 여부를 확인하고 있어요'}</h1>
            </div>
            <section className={styles.ticket} aria-label="사이트 접속 대기 안내">
                <div className={styles.position} role="status" aria-live="polite" aria-atomic="true">
                    {waiting && !cancelled ? <><span className={styles.caption}>내 앞 대기 인원</span><div className={styles.number}>{Number(status.ahead).toLocaleString('ko-KR')}<small>명</small></div><p>순서가 되면 자동으로 입장합니다.</p></> : <p>{cancelled ? '다시 대기하면 새로운 순서로 안내합니다.' : '잠시 후 자동으로 다시 확인합니다.'}</p>}
                </div>
                <div className={styles.instructions}>
                    {waiting && !cancelled && <p className={styles.refreshNote}>새로고침해도 대기 순서는 유지됩니다.</p>}
                    {error && <p className={styles.error} role="alert">{error}</p>}
                    {cancelled ? <button className={styles.primary} disabled={leaving} onClick={() => { setError(''); setStatus(null); setCancelled(false); }}>다시 대기하기</button>
                        : <button className={styles.secondary} disabled={leaving} onClick={leave}>대기 취소</button>}
                </div>
            </section>
        </main>
    </div>;
}
