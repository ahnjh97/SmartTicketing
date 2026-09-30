import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function FindAccountPage() {
    const navigate = useNavigate();
    const [email, setEmail] = useState("");
    const [code, setCode] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [newPasswordConfirm, setNewPasswordConfirm] = useState("");
    const [foundLoginId, setFoundLoginId] = useState("");
    const [codeSent, setCodeSent] = useState(false);
    const [message, setMessage] = useState("");

    const { error, setError, pending, run } = useAsyncAction();

    async function findLoginId(event) {
        event.preventDefault();
        setMessage("");
        setFoundLoginId("");

        run(async () => {
            const data = await authApi.findLoginId({
                email: email.trim()
            });
            setFoundLoginId(data.loginId);
            setMessage("가입된 아이디를 찾았습니다.");
        });
    }

    async function sendCode(event) {
        event.preventDefault();
        setMessage("");

        run(async () => {
            await authApi.requestPasswordReset({
                email: email.trim()
            });
            setCodeSent(true);
            setMessage("인증코드를 이메일로 보냈습니다. 5분 안에 입력해주세요.");
        });
    }

    async function resetPassword(event) {
        event.preventDefault();
        setMessage("");

        if (newPassword !== newPasswordConfirm) {
            setError("새 비밀번호가 일치하지 않습니다.");
            return;
        }

        run(async () => {
            await authApi.resetPassword({
                email: email.trim(),
                code: code.trim(),
                newPassword
            });
            setMessage("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.");
            setCode("");
            setNewPassword("");
            setNewPasswordConfirm("");
            setCodeSent(false);
        });
    }

    return (
        <AuthFormLayout error={error} message={message}>
            <h2>아이디 / 비밀번호 찾기</h2>
            <p className="subtitle">
                가입할 때 사용한 이메일로 계정을 확인할 수 있습니다.
            </p>

            <section>
                <h3>아이디 찾기</h3>
                <form onSubmit={findLoginId}>
                    <label htmlFor="find-account-email">이메일</label>
                    <input
                        id="find-account-email"
                        type="email"
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        placeholder="가입 이메일"
                        autoComplete="email"
                        required
                    />
                    <button type="submit" className="primary-button" disabled={pending}>
                        아이디 찾기
                    </button>
                </form>

                {foundLoginId && (
                    <p className="success-message field-message" role="status">
                        가입된 아이디: {foundLoginId}
                    </p>
                )}
            </section>

            <section>
                <h3>비밀번호 재설정</h3>
                <form onSubmit={sendCode}>
                    <label htmlFor="reset-account-email">이메일</label>
                    <input
                        id="reset-account-email"
                        type="email"
                        value={email}
                        onChange={(event) => setEmail(event.target.value)}
                        placeholder="가입 이메일"
                        autoComplete="email"
                        required
                    />
                    <button type="submit" className="signup-button" disabled={pending}>
                        인증코드 받기
                    </button>
                </form>

                {codeSent && (
                    <form onSubmit={resetPassword}>
                        <label htmlFor="reset-code">인증코드</label>
                        <input
                            id="reset-code"
                            type="text"
                            inputMode="numeric"
                            maxLength={6}
                            value={code}
                            onChange={(event) =>
                                setCode(event.target.value.replace(/\D/g, ""))
                            }
                            placeholder="6자리 인증코드"
                            required
                        />

                        <label htmlFor="new-password">새 비밀번호</label>
                        <input
                            id="new-password"
                            type="password"
                            minLength={8}
                            maxLength={100}
                            value={newPassword}
                            onChange={(event) => setNewPassword(event.target.value)}
                            placeholder="8~100자"
                            autoComplete="new-password"
                            required
                        />

                        <label htmlFor="new-password-confirm">새 비밀번호 확인</label>
                        <input
                            id="new-password-confirm"
                            type="password"
                            minLength={8}
                            maxLength={100}
                            value={newPasswordConfirm}
                            onChange={(event) =>
                                setNewPasswordConfirm(event.target.value)
                            }
                            placeholder="새 비밀번호를 다시 입력해주세요."
                            autoComplete="new-password"
                            required
                        />

                        <button type="submit" className="primary-button" disabled={pending}>
                            비밀번호 변경
                        </button>
                    </form>
                )}
            </section>

            <button
                type="button"
                className="text-button"
                onClick={() => navigate(PAGE_PATHS.login)}
            >
                로그인으로 돌아가기
            </button>
        </AuthFormLayout>
    );
}
