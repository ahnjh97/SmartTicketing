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

const EMAIL_PATTERN =
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const SOCIAL_PROVIDERS = [
    "GOOGLE",
    "NAVER",
    "KAKAO"
];

export default function SocialSignupPage() {
    const navigate = useNavigate();

    const [searchParams] =
        useSearchParams();

    const social =
        searchParams
            .get("social")
            ?.toUpperCase() || null;

    const isSocial =
        SOCIAL_PROVIDERS.includes(
            social
        );

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
        isSocial
        && Boolean(name.trim())
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

                if (!trimmed) {
                    setError(
                        "이메일은 필수입니다."
                    );
                    return false;
                }

                if (
                    !EMAIL_PATTERN.test(
                        trimmed
                    )
                ) {
                    setError(
                        "올바른 이메일 형식으로 입력해주세요."
                    );
                    return false;
                }

                if (
                    trimmed.length > 255
                ) {
                    setError(
                        "이메일은 255자 이하로 입력해주세요."
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
                            "이메일 중복확인 응답을 처리할 수 없습니다."
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
                        || "이메일 중복확인에 실패했습니다."
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

        const loadSocialSignupInfo =
            async () => {
                try {
                    const data =
                        await authApi.socialSignupInfo();

                    if (!active) {
                        return;
                    }

                    setName(
                        data.name ?? ""
                    );

                    if (
                        data.provider ===
                        "GOOGLE"
                        || data.provider ===
                        "NAVER"
                    ) {
                        const email =
                            data.email ?? "";

                        setLoginId(
                            email
                        );

                        if (email) {
                            const available =
                                await checkLoginId(
                                    email
                                );

                            if (
                                !active
                                || !available
                            ) {
                                return;
                            }
                        }
                    }

                    if (
                        data.provider ===
                        "KAKAO"
                    ) {
                        setLoginId(
                            ""
                        );

                        setLoginIdCheckStatus(
                            "idle"
                        );

                        setCheckedLoginId(
                            ""
                        );
                    }
                } catch (error) {
                    if (!active) {
                        return;
                    }

                    setError(
                        error.message
                        || "소셜 회원가입 정보를 가져오지 못했습니다."
                    );
                }
            };

        loadSocialSignupInfo();

        return () => {
            active = false;
        };
    }, [
        isSocial,
        checkLoginId,
        setError
    ]);

    function handleLoginIdChange(event) {
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

        setCheckedLoginId(
            ""
        );

        setError("");
    }

    async function handleCheckLoginId() {
        await checkLoginId(
            loginId
        );
    }

    function handleSubmit(event) {
        event.preventDefault();

        if (!isSocial) {
            setError(
                "잘못된 소셜 회원가입 요청입니다."
            );
            return;
        }

        if (
            !EMAIL_PATTERN.test(
                loginId.trim()
            )
        ) {
            setError(
                "올바른 이메일 형식으로 입력해주세요."
            );
            return;
        }

        if (
            loginIdCheckStatus !==
            "available"
            || checkedLoginId !==
            loginId.trim()
        ) {
            setError(
                "이메일 중복확인을 완료해주세요."
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
            const response =
                await authApi.socialSignup({
                    loginId:
                        loginId.trim(),
                    password,
                    birthDate
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
        });
    }

    return (
        <AuthFormLayout
            error={error}
        >
            <h2>
                소셜 회원가입
            </h2>

            <p className="subtitle">
                {social} 계정으로
                회원가입을 진행합니다.
            </p>

            <form
                onSubmit={handleSubmit}
            >
                <label htmlFor="signup-name">
                    이름
                </label>

                <input
                    id="signup-name"
                    type="text"
                    value={name}
                    readOnly
                    placeholder="이름"
                    required
                />

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

                <label htmlFor="signup-login-id">
                    이메일
                </label>

                <div className="login-id-row">
                    <input
                        id="signup-login-id"
                        type="email"
                        value={loginId}
                        onChange={
                            handleLoginIdChange
                        }
                        placeholder="이메일을 입력해주세요."
                        autoComplete="email"
                        readOnly={
                            isEmailLocked
                        }
                        required
                        className="login-id-input"
                    />

                    <button
                        type="button"
                        className="logout-button login-id-check-button"
                        onClick={
                            handleCheckLoginId
                        }
                        disabled={
                            pending
                            || isEmailLocked
                            || loginIdCheckStatus ===
                            "checking"
                        }
                    >
                        {
                            loginIdCheckStatus ===
                            "checking"
                                ? "확인 중..."
                                : "중복확인"
                        }
                    </button>
                </div>

                {
                    loginIdCheckStatus ===
                    "available" && (
                        <p
                            className="success-message field-message"
                            role="status"
                        >
                            사용 가능한 이메일입니다.
                        </p>
                    )
                }

                {
                    loginIdCheckStatus ===
                    "taken" && (
                        <p
                            className="error-message field-message"
                            role="alert"
                        >
                            이미 사용 중인 이메일입니다.
                        </p>
                    )
                }

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

                {
                    passwordConfirm && (
                        <p
                            className={
                                passwordsMatch
                                    ? "success-message field-message"
                                    : "error-message field-message"
                            }
                            aria-live="polite"
                        >
                            {
                                passwordsMatch
                                    ? "비밀번호가 일치합니다."
                                    : "비밀번호가 일치하지 않습니다."
                            }
                        </p>
                    )
                }

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