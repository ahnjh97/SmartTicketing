import { useRef, useState } from "react";
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
    const [passwordConfirm, setPasswordConfirm] = useState("");
    const [loginIdCheckStatus, setLoginIdCheckStatus] = useState("idle");
    const [checkedLoginId, setCheckedLoginId] = useState("");
    const checkVersion = useRef(0);
    const { error, setError, pending, run } = useAsyncAction();
    const passwordsMatch = password === passwordConfirm;
    const canSubmit = loginIdCheckStatus === "available"
        && checkedLoginId === loginId.trim() && password.length >= 8 && passwordsMatch;

    function handleLoginIdChange(event) {
        checkVersion.current++;
        setLoginId(event.target.value);
        setLoginIdCheckStatus("idle");
        setCheckedLoginId("");
        setError("");
    }

    async function checkSignupLoginId() {
        const version = ++checkVersion.current;
        const value = loginId.trim();
        setError("");
        setCheckedLoginId("");
        setLoginIdCheckStatus("idle");
        if (value.length < 4 || value.length > 50) {
            setError("아이디는 4~50자로 입력해주세요.");
            return;
        }
        if (!/^[A-Za-z0-9_]+$/.test(value)) {
            setError("아이디는 영문, 숫자, 밑줄만 사용할 수 있습니다.");
            return;
        }
        setLoginIdCheckStatus("checking");
        try {
            const data = await authApi.checkLoginId(value);
            // 확인 중 아이디를 바꿨다면 이전 응답으로 가입을 허용하지 않는다.
            if (version !== checkVersion.current) return;
            if (typeof data?.available !== "boolean") {
                throw new Error("아이디 중복확인 응답을 처리할 수 없습니다.");
            }
            setCheckedLoginId(value);
            setLoginIdCheckStatus(data.available ? "available" : "taken");
        } catch (error) {
            if (version !== checkVersion.current) return;
            setLoginIdCheckStatus("idle");
            setError(error.message || "아이디 중복확인에 실패했습니다.");
        }
    }

    function handleSubmit(event) {
        event.preventDefault();
        if (loginIdCheckStatus !== "available" || checkedLoginId !== loginId.trim()) {
            setError("아이디 중복확인을 완료해주세요.");
            return;
        }
        if (!passwordsMatch) {
            setError("비밀번호가 일치하지 않습니다.");
            return;
        }
        run(async () => {
            await authApi.signup({ name, birthDate, loginId: loginId.trim(), password });
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
                <div style={{ display: "flex", gap: "8px", alignItems: "stretch" }}>
                    <input id="signup-login-id" type="text" value={loginId}
                        onChange={handleLoginIdChange} placeholder="영문, 숫자, 밑줄 4~50자"
                        autoComplete="username" required style={{ flex: 1, minWidth: 0 }} />
                    <button type="button" className="logout-button" onClick={checkSignupLoginId}
                        disabled={pending || loginIdCheckStatus === "checking"}
                        style={{ whiteSpace: "nowrap" }}>
                        {loginIdCheckStatus === "checking" ? "확인 중..." : "중복확인"}
                    </button>
                </div>
                {loginIdCheckStatus === "available" && (
                    <p className="success-message" role="status" style={{ marginTop: "8px", marginBottom: 0 }}>
                        사용가능한 아이디 입니다.
                    </p>
                )}
                {loginIdCheckStatus === "taken" && (
                    <p className="error-message" role="alert" style={{ marginTop: "8px", marginBottom: 0 }}>
                        이미 사용 중인 아이디입니다.
                    </p>
                )}
                <label htmlFor="signup-password">비밀번호</label>
                <input id="signup-password" type="password" value={password}
                    onChange={(event) => setPassword(event.target.value)} placeholder="8~100자"
                    autoComplete="new-password" required />
                <label htmlFor="signup-password-confirm">비밀번호 확인</label>
                <input id="signup-password-confirm" type="password" value={passwordConfirm}
                    onChange={(event) => setPasswordConfirm(event.target.value)}
                    placeholder="비밀번호를 다시 입력해주세요." autoComplete="new-password" required />
                {passwordConfirm && (
                    <p className={passwordsMatch ? "success-message" : "error-message"}
                        style={{ marginTop: "8px", marginBottom: 0 }} aria-live="polite">
                        {passwordsMatch ? "비밀번호가 일치합니다." : "비밀번호가 일치하지 않습니다."}
                    </p>
                )}
                <button type="submit" disabled={pending || !canSubmit} className="primary-button">회원가입</button>
            </form>
            <button type="button" className="text-button" onClick={() => navigate(PAGE_PATHS.login)}>
                로그인으로 돌아가기
            </button>
        </AuthFormLayout>
    );
}
