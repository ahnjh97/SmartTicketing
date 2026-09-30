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
    const [message, setMessage] = useState(
        location.state?.message ?? ""
    );
    const { login, sessionError } = useAuth();
    const { error, pending, run } = useAsyncAction();

    function handleSubmit(event) {
        event.preventDefault();
        setMessage("");

        run(async () => {
            await login({
                loginId,
                password
            });

            navigate(
                PAGE_PATHS.profile,
                { replace: true }
            );
        });
    }

    function renderSocialLogo(provider) {
        if (provider === "google") {
            return (
                <svg
                    className="social-logo google-logo"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                >
                    <path
                        fill="#4285F4"
                        d="M21.35 12.27c0-.71-.06-1.4-.18-2.05H12v3.88h5.24a4.48 4.48 0 0 1-1.94 2.94v2.45h3.14c1.84-1.69 2.91-4.18 2.91-7.22Z"
                    />
                    <path
                        fill="#34A853"
                        d="M12 21.75c2.63 0 4.84-.87 6.45-2.36l-3.14-2.45c-.87.58-1.98.92-3.31.92-2.54 0-4.69-1.72-5.46-4.03H3.3v2.53A9.75 9.75 0 0 0 12 21.75Z"
                    />
                    <path
                        fill="#FBBC05"
                        d="M6.54 13.83A5.86 5.86 0 0 1 6.24 12c0-.64.11-1.26.3-1.83V7.64H3.3A9.74 9.74 0 0 0 2.25 12c0 1.57.38 3.06 1.05 4.36l3.24-2.53Z"
                    />
                    <path
                        fill="#EA4335"
                        d="M12 6.14c1.43 0 2.71.49 3.72 1.45l2.79-2.79C16.84 3.22 14.63 2.25 12 2.25a9.75 9.75 0 0 0-8.7 5.39l3.24 2.53c.77-2.31 2.92-4.03 5.46-4.03Z"
                    />
                </svg>
            );
        }

        if (provider === "naver") {
            return (
                <svg
                    className="social-logo naver-logo"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                >
                    <path
                        fill="currentColor"
                        d="M4 4h5.08l5.92 8.03V4H20v16h-5.08L9 11.97V20H4V4Z"
                    />
                </svg>
            );
        }

        return (
            <svg
                className="social-logo kakao-logo"
                viewBox="0 0 24 24"
                aria-hidden="true"
            >
                <path
                    fill="currentColor"
                    d="M12 4C7.03 4 3 7.14 3 11.02c0 2.5 1.65 4.7 4.16 5.94L6.2 20.5c-.08.28.23.5.48.34l4.04-2.55c.42.06.85.09 1.28.09 4.97 0 9-3.14 9-7.02S16.97 4 12 4Z"
                />
            </svg>
        );
    }

    return (
        <AuthFormLayout
            error={error || sessionError}
            message={message}
        >
            <form onSubmit={handleSubmit}>
                <label htmlFor="login-id">
                    아이디
                </label>

                <input
                    id="login-id"
                    type="text"
                    value={loginId}
                    onChange={(event) =>
                        setLoginId(event.target.value)
                    }
                    placeholder="아이디"
                    autoComplete="username"
                    required
                />

                <label htmlFor="login-password">
                    비밀번호
                </label>

                <input
                    id="login-password"
                    type="password"
                    value={password}
                    onChange={(event) =>
                        setPassword(event.target.value)
                    }
                    placeholder="비밀번호"
                    autoComplete="current-password"
                    required
                />

                <div className="login-action-buttons">
                    <button
                        type="submit"
                        disabled={pending}
                        className="primary-button"
                    >
                        로그인
                    </button>

                    <button
                        type="button"
                        className="signup-button"
                        onClick={() =>
                            navigate(PAGE_PATHS.signup)
                        }
                    >
                        회원가입
                    </button>
                </div>
            </form>
            <button type="button" className="text-button" onClick={() => navigate(PAGE_PATHS.findAccount)}>
                비밀번호 재설정
            </button>

            <div className="divider"><span>또는</span></div>
            <div className="social-buttons">
                {["google", "naver", "kakao"].map(
                    (provider) => (
                        <button
                            key={provider}
                            type="button"
                            className={`social-button ${provider}`}
                            disabled={pending}
                            onClick={() =>
                                window.location.assign(
                                    authApi.socialLoginUrl(
                                        provider
                                    )
                                )
                            }
                            aria-label={`${provider} 로그인`}
                        >
                            {renderSocialLogo(provider)}
                        </button>
                    )
                )}
            </div>
        </AuthFormLayout>
    );
}