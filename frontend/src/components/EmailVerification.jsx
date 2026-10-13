import FormButton from './FormButton.jsx';
import { useEffect, useRef, useState } from 'react';
import { authApi } from '../api/auth.js';

export default function EmailVerification({ purpose, onChange, disabled = false, fixedEmail = null }) {
    const [email, setEmail] = useState(fixedEmail ?? '');
    const [code, setCode] = useState('');
    const [sent, setSent] = useState(false);
    const [verified, setVerified] = useState(false);
    const [pending, setPending] = useState(false);
    const [error, setError] = useState('');
    const [cooldown, setCooldown] = useState(0);
    const revision = useRef(0);
    useEffect(() => {
        if (!cooldown) return;
        const timer = setTimeout(() => setCooldown(value => Math.max(0, value - 1)), 1000);
        return () => clearTimeout(timer);
    }, [cooldown]);
    useEffect(() => () => { revision.current++; }, []);

    async function submit(verify) {
        const version = ++revision.current;
        const normalized = email.trim().toLowerCase();
        setPending(true);
        setError('');
        try {
            if (verify) {
                const result = await authApi.verifyEmail({ email: normalized, purpose, code });
                if (version !== revision.current) return;
                if (!result?.verificationToken) throw new Error('인증 결과를 확인하지 못했습니다. 다시 시도해주세요.');
                setVerified(true);
                onChange({ email: normalized, verificationToken: result.verificationToken });
            } else {
                await authApi.sendEmail({ email: normalized, purpose });
                if (version !== revision.current) return;
                setSent(true);
                setCode('');
                setCooldown(60);
            }
        } catch (e) {
            if (version === revision.current) setError(e.message);
        } finally {
            if (version === revision.current) setPending(false);
        }
    }

    return <fieldset disabled={disabled || pending} className="email-verification">
        <label htmlFor={`email-${purpose}`}>이메일</label>
        <div className="login-id-row">
            <input id={`email-${purpose}`} type="email" autoComplete="email" maxLength={255}
                placeholder="이메일" readOnly={fixedEmail !== null} value={email} required onChange={event => {
                    revision.current++;
                    setEmail(event.target.value); setCode(''); setSent(false); setVerified(false); setError('');
                    onChange(null);
                }} />
            <FormButton type="button" variant="secondary" disabled={verified || cooldown > 0 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())}
                onClick={() => submit(false)}>{cooldown ? `${cooldown}초 후 재발송` : sent ? '인증번호 재발송' : '인증번호 발송'}</FormButton>
        </div>
        {sent && !verified && <>
            <p role="status" className="auth-help">메일로 받은 인증번호를 10분 안에 입력해주세요.</p>
            <label htmlFor={`code-${purpose}`}>인증번호</label>
            <div className="login-id-row">
                <input id={`code-${purpose}`} inputMode="numeric" autoComplete="one-time-code" maxLength={6}
                    placeholder="6자리 인증번호" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} />
                <FormButton type="button" variant="secondary" disabled={code.length !== 6} onClick={() => submit(true)}>인증 확인</FormButton>
            </div>
        </>}
        {verified && <>
            <p role="status" className="success-message">이메일 인증이 완료되었습니다. 10분 안에 진행해주세요.</p>
            <FormButton type="button" variant="quiet" onClick={() => { setVerified(false); setSent(false); setCode(''); onChange(null); }}>다시 인증하기</FormButton>
        </>}
        {error && <p role="alert" className="error-message">{error}</p>}
    </fieldset>;
}
