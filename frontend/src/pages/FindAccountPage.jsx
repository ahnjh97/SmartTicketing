import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function FindAccountPage() {
    const navigate = useNavigate();
    const [identifier, setIdentifier] = useState("");
    const [confirmedIdentifier, setConfirmedIdentifier] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [newPasswordConfirm, setNewPasswordConfirm] = useState("");
    const [message, setMessage] = useState("");

    const { error, setError, pending, run } = useAsyncAction();

    function continueToReset(event) {
        event.preventDefault();
        setError("");
        setMessage("");
        setConfirmedIdentifier(identifier.trim());
    }

    async function resetPassword(event) {
        event.preventDefault();
        setError("");
        setMessage("");

        if (newPassword !== newPasswordConfirm) {
            setError("새 비밀번호가 일치하지 않습니다.");
            return;
        }

        run(async () => {
            await authApi.resetPassword({
                identifier: confirmedIdentifier,
                newPassword
            });

            setMessage(
                "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요."
            );
            setNewPassword("");
            setNewPasswordConfirm("");
        });
    }

    return (
        <AuthFormLayout error={error} message={message}>
            <h2>비밀번호 재설정</h2>

            {!confirmedIdentifier ? (
                <>
                    <p className="subtitle">
                        소셜 계정은 가입한 이메일, 일반 계정은 아이디를 입력해주세요.
                    </p>

                    <form onSubmit={continueToReset}>
                        <label htmlFor="account-identifier">
                            아이디 또는 이메일
                        </label>
                        <input
                            id="account-identifier"
                            type="text"
                            value={identifier}
                            onChange={(event) =>
                                setIdentifier(event.target.value)
                            }
                            placeholder="일반 계정 아이디 또는 소셜 계정 이메일"
                            autoComplete="username"
                            required
                        />

                        <button
                            type="submit"
                            className="primary-button"
                            disabled={pending}
                        >
                            비밀번호 재설정
                        </button>
                    </form>
                </>
            ) : (
                <>
                    <p className="subtitle">
                        <strong>{confirmedIdentifier}</strong> 계정의 새 비밀번호를
                        입력해주세요.
                    </p>

                    <form onSubmit={resetPassword}>
                        <label htmlFor="new-password">새 비밀번호</label>
                        <input
                            id="new-password"
                            type="password"
                            minLength={8}
                            maxLength={100}
                            value={newPassword}
                            onChange={(event) =>
                                setNewPassword(event.target.value)
                            }
                            placeholder="8~100자"
                            autoComplete="new-password"
                            required
                        />

                        <label htmlFor="new-password-confirm">
                            새 비밀번호 확인
                        </label>
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

                        <button
                            type="submit"
                            className="primary-button"
                            disabled={pending}
                        >
                            비밀번호 변경
                        </button>
                    </form>

                    <button
                        type="button"
                        className="text-button"
                        onClick={() => {
                            setConfirmedIdentifier("");
                            setNewPassword("");
                            setNewPasswordConfirm("");
                            setMessage("");
                            setError("");
                        }}
                    >
                        다른 계정으로 재설정
                    </button>
                </>
            )}

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
