import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function SignupPage() {
    const navigate = useNavigate();
    const [name, setName] = useState("");
    const [birthDate, setBirthDate] = useState("");
    const [loginId, setLoginId] = useState("");
    const [password, setPassword] = useState("");
    const { error, pending, run } = useAsyncAction();

    function handleSubmit(event) {
        event.preventDefault();
        run(async () => {
            await authApi.signup({ name, birthDate, loginId, password });
            navigate(PAGE_PATHS.login, {
                replace: true, state: { message: "회원가입이 완료되었습니다. 로그인해주세요." },
            });
        });
    }

    return (
        <AuthFormLayout error={error}>
            <h2>일반 회원가입</h2>
            <form onSubmit={handleSubmit}>
                <label htmlFor="signup-name">이름</label>
                <input id="signup-name" type="text" value={name}
                    onChange={(event) => setName(event.target.value)} placeholder="이름" required />
                <label htmlFor="signup-birth-date">생년월일</label>
                <input id="signup-birth-date" type="date" value={birthDate}
                    onChange={(event) => setBirthDate(event.target.value)} required />
                <label htmlFor="signup-login-id">아이디</label>
                <input id="signup-login-id" type="text" value={loginId}
                    onChange={(event) => setLoginId(event.target.value)} placeholder="영문, 숫자, 밑줄 4~50자"
                    autoComplete="username" required />
                <label htmlFor="signup-password">비밀번호</label>
                <input id="signup-password" type="password" value={password}
                    onChange={(event) => setPassword(event.target.value)} placeholder="8~100자"
                    autoComplete="new-password" required />
                <button type="submit" disabled={pending} className="primary-button">회원가입</button>
            </form>
            <button type="button" className="text-button" onClick={() => navigate(PAGE_PATHS.login)}>
                로그인으로 돌아가기
            </button>
        </AuthFormLayout>
    );
}
