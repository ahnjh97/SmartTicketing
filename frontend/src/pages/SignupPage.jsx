import {
    useCallback,
    useEffect,
    useRef,
    useState
} from "react";

import {
    useNavigate,
    useSearchParams
} from "react-router-dom";

import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import { setAccessToken } from "../auth/session.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function SignupPage() {

    const navigate = useNavigate();

    const [searchParams] =
        useSearchParams();

    const social =
        searchParams
            .get("social")
            ?.toUpperCase() || null;

    const isSocial =
        ["GOOGLE", "NAVER", "KAKAO"]
            .includes(social);

    const isEmailLocked =
        social === "GOOGLE"
        || social === "NAVER";

    const [name, setName] =
        useState("");

    const [birthDate, setBirthDate] =
        useState("");

    const [loginId, setLoginId] =
        useState("");

    const [password, setPassword] =
        useState("");

    const [passwordConfirm, setPasswordConfirm] =
        useState("");

    const [
        loginIdCheckStatus,
        setLoginIdCheckStatus
    ] = useState("idle");

    const [
        checkedLoginId,
        setCheckedLoginId
    ] = useState("");

    const checkVersion =
        useRef(0);

    const {
        error,
        setError,
        pending,
        run
    } = useAsyncAction();

    const passwordsMatch =
        password === passwordConfirm;

    const canSubmit =
        Boolean(name.trim())
        && Boolean(birthDate)
        && loginIdCheckStatus === "available"
        && checkedLoginId === loginId.trim()
        && password.length >= 8
        && passwordsMatch;

    const checkLoginId =
        useCallback(
            async (value) => {

                const version =
                    ++checkVersion.current;

                const trimmed =
                    value.trim();

                setError("");
                setCheckedLoginId("");
                setLoginIdCheckStatus(
                    "idle"
                );

                if (
                    trimmed.length < 4
                    || trimmed.length > 255
                ) {
                    setError(
                        "아이디는 4~255자로 입력해주세요."
                    );
                    return false;
                }

                if (
                    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/
                        .test(trimmed)
                ) {
                    setError(
                        "올바른 이메일 형식으로 입력해주세요."
                    );
                    return false;
                }

                setLoginIdCheckStatus(
                    "checking"
                );

                try {

                    const data =
                        await authApi.checkLoginId(
                            trimmed
                        );

                    if (
                        version !==
                        checkVersion.current
                    ) {
                        return false;
                    }

                    if (
                        typeof data?.available
                        !== "boolean"
                    ) {
                        throw new Error(
                            "아이디 중복확인 응답을 처리할 수 없습니다."
                        );
                    }

                    setCheckedLoginId(
                        trimmed
                    );

                    setLoginIdCheckStatus(
                        data.available
                            ? "available"
                            : "taken"
                    );

                    return data.available;

                } catch (error) {

                    if (
                        version !==
                        checkVersion.current
                    ) {
                        return false;
                    }

                    setLoginIdCheckStatus(
                        "idle"
                    );

                    setError(
                        error.message
                        || "아이디 중복확인에 실패했습니다."
                    );

                    return false;
                }
            },
            [setError]
        );

    useEffect(() => {

        if (!isSocial) {
            return;
        }

        let active = true;

        run(async () => {

            const data =
                await authApi.socialSignupInfo();

            if (!active) {
                return;
            }

            setName(
                data.name ?? ""
            );

            if (
                data.provider === "GOOGLE"
                || data.provider === "NAVER"
            ) {

                const email =
                    data.email ?? "";

                setLoginId(
                    email
                );


                if (email) {
                    await checkLoginId(
                        email
                    );
                }
            }

            if (
                data.provider === "KAKAO"
            ) {
                setLoginId("");
                setLoginIdCheckStatus(
                    "idle"
                );
                setCheckedLoginId("");
            }

        }).catch(() => {

        });

        return () => {
            active = false;
        };

    }, [
        isSocial,
        checkLoginId,
        run
    ]);

    function handleLoginIdChange(
        event
    ) {

        if (isEmailLocked) {
            return;
        }

        checkVersion.current++;

        setLoginId(
            event.target.value
        );

        setLoginIdCheckStatus(
            "idle"
        );

        setCheckedLoginId("");

        setError("");
    }

    async function handleCheckLoginId() {
        await checkLoginId(
            loginId
        );
    }

    function handleSubmit(event) {

        event.preventDefault();

        if (
            loginIdCheckStatus !==
            "available"
            || checkedLoginId !==
            loginId.trim()
        ) {
            setError(
                "아이디 중복확인을 완료해주세요."
            );
            return;
        }

        if (!passwordsMatch) {
            setError(
                "비밀번호가 일치하지 않습니다."
            );
            return;
        }

        run(async () => {

            if (isSocial) {

                const response =
                    await authApi.socialSignup({
                        loginId:
                            loginId.trim(),
                        password,
                        birthDate,
                    });

                if (
                    !response?.accessToken
                ) {
                    throw new Error(
                        "회원가입 응답을 처리할 수 없습니다."
                    );
                }

                setAccessToken(
                    response.accessToken
                );

                window.location.assign(
                    PAGE_PATHS.preferenceSetup
                );

                return;
            }

            await authApi.signup({
                name,
                birthDate,
                loginId:
                    loginId.trim(),
                password,
            });

            navigate(
                PAGE_PATHS.login,
                {
                    replace: true,
                    state: {
                        message:
                            "회원가입이 완료되었습니다. 로그인해주세요.",
                    },
                }
            );
        });
    }

    return (
        <AuthFormLayout
            error={error}
        >

            <h2>
                {isSocial
                    ? "회원가입"
                    : "일반 회원가입"}
            </h2>

            {isSocial && (
                <p className="subtitle">
                    {social} 계정으로
                    회원가입을 진행합니다.
                </p>
            )}

            <form
                onSubmit={handleSubmit}
            >

                {/* ==================== */}
                {/* 이름 */}
                {/* ==================== */}

                <label htmlFor="signup-name">
                    이름
                </label>

                <input
                    id="signup-name"
                    type="text"
                    value={name}
                    onChange={(event) =>
                        setName(
                            event.target.value
                        )
                    }
                    placeholder="이름"
                    readOnly={isSocial}
                    required
                />

                {/* ==================== */}
                {/* 생년월일 */}
                {/* ==================== */}

                <label htmlFor="signup-birth-date">
                    생년월일
                </label>

                <input
                    id="signup-birth-date"
                    type="date"
                    value={birthDate}
                    onChange={(event) =>
                        setBirthDate(
                            event.target.value
                        )
                    }
                    required
                />

                {/* ==================== */}
                {/* 아이디 */}
                {/* ==================== */}

                <label htmlFor="signup-login-id">
                    아이디
                </label>

                <div
                    style={{
                        display: "flex",
                        gap: "8px",
                        alignItems: "stretch",
                    }}
                >

                    <input
                        id="signup-login-id"
                        type="email"
                        value={loginId}
                        onChange={
                            handleLoginIdChange
                        }
                        placeholder="이메일을 입력해주세요."
                        autoComplete="username"
                        readOnly={
                            isEmailLocked
                        }
                        required
                        style={{
                            flex: 1,
                            minWidth: 0,
                        }}
                    />

                    <button
                        type="button"
                        className="logout-button"
                        onClick={
                            handleCheckLoginId
                        }
                        disabled={
                            pending
                            || isEmailLocked
                            || loginIdCheckStatus ===
                            "checking"
                        }
                        style={{
                            whiteSpace: "nowrap",
                        }}
                    >
                        {
                            loginIdCheckStatus ===
                            "checking"
                                ? "확인 중..."
                                : "중복확인"
                        }
                    </button>

                </div>

                {loginIdCheckStatus ===
                    "available" && (

                        <p
                            className="success-message"
                            role="status"
                            style={{
                                marginTop: "8px",
                                marginBottom: 0,
                            }}
                        >
                            사용 가능한 아이디입니다.
                        </p>
                    )}

                {loginIdCheckStatus ===
                    "taken" && (

                        <p
                            className="error-message"
                            role="alert"
                            style={{
                                marginTop: "8px",
                                marginBottom: 0,
                            }}
                        >
                            이미 사용 중인 아이디입니다.
                        </p>
                    )}

                {/* ==================== */}
                {/* 비밀번호 */}
                {/* ==================== */}

                <label htmlFor="signup-password">
                    비밀번호
                </label>

                <input
                    id="signup-password"
                    type="password"
                    value={password}
                    onChange={(event) =>
                        setPassword(
                            event.target.value
                        )
                    }
                    placeholder="8~100자"
                    autoComplete="new-password"
                    required
                />

                {/* ==================== */}
                {/* 비밀번호 확인 */}
                {/* ==================== */}

                <label htmlFor="signup-password-confirm">
                    비밀번호 확인
                </label>

                <input
                    id="signup-password-confirm"
                    type="password"
                    value={passwordConfirm}
                    onChange={(event) =>
                        setPasswordConfirm(
                            event.target.value
                        )
                    }
                    placeholder="비밀번호를 다시 입력해주세요."
                    autoComplete="new-password"
                    required
                />

                {passwordConfirm && (
                    <p
                        className={
                            passwordsMatch
                                ? "success-message"
                                : "error-message"
                        }
                        style={{
                            marginTop: "8px",
                            marginBottom: 0,
                        }}
                        aria-live="polite"
                    >
                        {passwordsMatch
                            ? "비밀번호가 일치합니다."
                            : "비밀번호가 일치하지 않습니다."}
                    </p>
                )}

                {/* ==================== */}
                {/* 가입 */}
                {/* ==================== */}

                <button
                    type="submit"
                    disabled={
                        pending
                        || !canSubmit
                    }
                    className="primary-button"
                >
                    회원가입
                </button>

            </form>

            <button
                type="button"
                className="text-button"
                onClick={() =>
                    navigate(
                        PAGE_PATHS.login
                    )
                }
            >
                로그인으로 돌아가기
            </button>

        </AuthFormLayout>
    );
}