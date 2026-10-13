import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth.js';
import AuthFormLayout from '../components/AuthFormLayout.jsx';
import EmailVerification from '../components/EmailVerification.jsx';
import useAsyncAction from '../hooks/useAsyncAction.js';
import { PAGE_PATHS } from '../navigation.js';

export default function FindAccountPage() {
    const navigate = useNavigate();
    const [mode, setMode] = useState('FIND_ID');
    const [proof, setProof] = useState(null);
    const [password, setPassword] = useState('');
    const [confirm, setConfirm] = useState('');
    const [message, setMessage] = useState('');
    const [ids, setIds] = useState(null);
    const [generation, setGeneration] = useState(0);
    const { error, setError, pending, run } = useAsyncAction();
    function changeMode(value) {
        setMode(value); setProof(null); setIds(null); setMessage(''); setError('');
        setPassword(''); setConfirm(''); setGeneration(value => value + 1);
    }
    function submit(event) {
        event.preventDefault();
        if (!proof || (mode === 'RESET_PASSWORD' && (password.length < 8 || password !== confirm))) return;
        run(async () => {
            if (mode === 'FIND_ID') {
                const result = await authApi.findLoginIds(proof);
                setIds(result.loginIds);
                setMessage(result.loginIds.length ? '인증된 이메일로 가입한 아이디입니다.' : '인증된 이메일로 가입한 계정이 없습니다.');
            } else {
                await authApi.resetPassword({ ...proof, newPassword: password });
                navigate(PAGE_PATHS.login, { replace: true, state: { message: '비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.' } });
            }
            setProof(null); setGeneration(value => value + 1);
        });
    }
    return <AuthFormLayout error={error} message={message}>
        <h2>아이디 / 비밀번호 찾기</h2>
        <div className="account-find-tabs">
            <button type="button" disabled={pending} className={mode === 'FIND_ID' ? 'active' : ''} aria-pressed={mode === 'FIND_ID'} onClick={() => changeMode('FIND_ID')}>아이디 찾기</button>
            <button type="button" disabled={pending} className={mode === 'RESET_PASSWORD' ? 'active' : ''} aria-pressed={mode === 'RESET_PASSWORD'} onClick={() => changeMode('RESET_PASSWORD')}>비밀번호 찾기</button>
        </div>
        <p className="auth-help">가입 시 인증한 이메일로 본인 확인을 진행해주세요.</p>
        {ids && <div className="account-choice-list">{ids.map(id => <div className="account-choice selected" key={id}>{id}</div>)}</div>}
        <form onSubmit={submit}>
            <EmailVerification key={`${mode}-${generation}`} purpose={mode} onChange={setProof} disabled={pending} />
            {mode === 'RESET_PASSWORD' && <>
                <label htmlFor="new-password">새 비밀번호</label>
                <input id="new-password" type="password" autoComplete="new-password" minLength={8} maxLength={100}
                    placeholder="8~100자" required value={password} onChange={e => setPassword(e.target.value)} />
                <label htmlFor="confirm-password">새 비밀번호 확인</label>
                <input id="confirm-password" type="password" autoComplete="new-password" maxLength={100}
                    required value={confirm} onChange={e => setConfirm(e.target.value)} />
                {confirm && password !== confirm && <p className="error-message field-message" role="alert">비밀번호가 일치하지 않습니다.</p>}
            </>}
            <button type="submit" className="primary-button" disabled={pending || !proof || (mode === 'RESET_PASSWORD' && (password.length < 8 || password !== confirm))}>
                {mode === 'FIND_ID' ? '아이디 확인' : '비밀번호 변경'}
            </button>
        </form>
        <button type="button" className="text-button" onClick={() => navigate(PAGE_PATHS.login)}>로그인으로 돌아가기</button>
    </AuthFormLayout>;
}
