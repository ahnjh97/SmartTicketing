import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function FindAccountPage() {
    const navigate = useNavigate();
    const [step, setStep] = useState(1);
    const [name, setName] = useState("");
    const [loginIds, setLoginIds] = useState([]);
    const [selectedLoginId, setSelectedLoginId] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [newPasswordConfirm, setNewPasswordConfirm] = useState("");
    const [message, setMessage] = useState("");

    const { error, setError, pending, run } = useAsyncAction();

    function findLoginIds(event) {
        event.preventDefault();
        setError("");
        setMessage("");

        run(async () => {
            const response = await authApi.findLoginIds({
                name: name.trim()
            });

            const ids = response?.loginIds ?? [];
            setLoginIds(ids);

            if (ids.length === 0) {
                setError("입력한 이름으로 가입된 계정을 찾을 수 없습니다.");
                return;
            }

            setSelectedLoginId(ids.length === 1 ? ids[0] : "");
            setStep(2);
        });
    }

    function selectAccount(event) {
        event.preventDefault();
        setError("");
        setMessage("");

        if (!selectedLoginId) {
            setError("비밀번호를 재설정할 아이디를 선택해주세요.");
            return;
        }

        setStep(3);
    }

    function resetPassword(event) {
        event.preventDefault();
        setError("");
        setMessage("");

        if (newPassword !== newPasswordConfirm) {
            setError("새 비밀번호가 일치하지 않습니다.");
            return;
        }

        run(async () => {
            await authApi.resetPassword({
                name: name.trim(),
                loginId: selectedLoginId,
                newPassword
            });

            setMessage(
                "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요."
            );
            setNewPassword("");
            setNewPasswordConfirm("");
        });
    }

    function restart() {
        setStep(1);
        setLoginIds([]);
        setSelectedLoginId("");
        setNewPassword("");
        setNewPasswordConfirm("");
        setMessage("");
        setError("");
    }

    return (
        <AuthFormLayout error={error} message={message}>
            <h2>아이디 / 비밀번호 찾기</h2>

            {step === 1 && (
                <>
                    <p className="subtitle">
                        가입할 때 입력한 이름으로 아이디를 찾아주세요.
                    </p>

                    <form onSubmit={findLoginIds}>
                        <label htmlFor="find-account-name">
                            이름
                        </label>

                        <input
                            id="find-account-name"
                            type="text"
                            value={name}
                            onChange={(event) =>
                                setName(event.target.value)
                            }
                            placeholder="이름"
                            autoComplete="name"
                            required
                        />

                        <button
                            type="submit"
                            className="primary-button"
                            disabled={pending}
                        >
                            아이디 찾기
                        </button>
                    </form>
                </>
            )}

            {step === 2 && (
                <>
                    <p className="subtitle">
                        <strong>{name.trim()}</strong>님으로 가입된 아이디입니다.
                        <br />
                        비밀번호를 재설정할 계정을 선택해주세요.
                    </p>

                    <form onSubmit={selectAccount}>
                        <div
                            role="radiogroup"
                            aria-label="비밀번호를 재설정할 아이디 선택"
                        >
                            {loginIds.map((loginId) => (
                                <label
                                    key={loginId}
                                    className="account-choice"
                                >
                                    <input
                                        type="radio"
                                        name="selected-login-id"
                                        value={loginId}
                                        checked={
                                            selectedLoginId === loginId
                                        }
                                        onChange={(event) =>
                                            setSelectedLoginId(
                                                event.target.value
                                            )
                                        }
                                    />
                                    <span>{loginId}</span>
                                </label>
                            ))}
                        </div>

                        <button
                            type="submit"
                            className="primary-button"
                            disabled={pending}
                        >
                            비밀번호 재설정
                        </button>
                    </form>

                    <button
                        type="button"
                        className="text-button"
                        onClick={restart}
                    >
                        다시 아이디 찾기
                    </button>
                </>
            )}

            {step === 3 && (
                <>
                    <p className="subtitle">
                        <strong>{selectedLoginId}</strong> 계정의 비밀번호를
                        재설정합니다.
                    </p>

                    <form onSubmit={resetPassword}>
                        <label htmlFor="reset-name">
                            이름
                        </label>

                        <input
                            id="reset-name"
                            type="text"
                            value={name}
                            readOnly
                        />

                        <label htmlFor="reset-login-id">
                            아이디
                        </label>

                        <input
                            id="reset-login-id"
                            type="text"
                            value={selectedLoginId}
                            readOnly
                        />

                        <label htmlFor="new-password">
                            새 비밀번호
                        </label>

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
                        onClick={() => setStep(2)}
                    >
                        아이디 선택으로 돌아가기
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
