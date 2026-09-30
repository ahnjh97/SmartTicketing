import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function LoginPage() {
    const location = useLocation();
    const navigate = useNavigate();
    const [loginId, setLoginId] = useState("");
    const [password, setPassword] = useState("");
    const [message, setMessage] = useState(location.state?.message ?? "");
    const { login, sessionError } = useAuth();
    const { error, pending, run } = useAsyncAction();

    function handleSubmit(event) {
        event.preventDefault();
        setMessage("");
        run(async () => {
            await login({ loginId, password });
            navigate(PAGE_PATHS.profile, { replace: true });
        });
    }

    return (
        <AuthFormLayout error={error || sessionError} message={message}>
            <div className="social-buttons">
                {["google", "naver", "kakao"].map((provider) => (
                    <button key={provider} type="button" className={`social-button ${provider}`}
                        disabled={pending} onClick={() => window.location.assign(authApi.socialLoginUrl(provider))}>
                        {provider[0].toUpperCase() + provider.slice(1)} 로그인
                    </button>
                ))}
            </div>
            <div className="divider"><span>또는</span></div>
            <form onSubmit={handleSubmit}>
                <label htmlFor="login-id">아이디</label>
                <input id="login-id" type="text" value={loginId}
                    onChange={(event) => setLoginId(event.target.value)} placeholder="아이디"
                    autoComplete="username" required />
                <label htmlFor="login-password">비밀번호</label>
                <input id="login-password" type="password" value={password}
                    onChange={(event) => setPassword(event.target.value)} placeholder="비밀번호"
                    autoComplete="current-password" required />
                <button type="submit" disabled={pending} className="primary-button">로그인</button>
            </form>
            <button type="button" className="text-button" onClick={() => navigate(PAGE_PATHS.signup)}>
                일반 회원가입
            </button>
        </AuthFormLayout>
    );
}
