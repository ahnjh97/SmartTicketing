import {
    useCallback,
    useRef,
    useState
} from "react";

import { useNavigate } from "react-router-dom";

import { PAGE_PATHS } from "../navigation.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { authApi } from "../api/auth.js";
import { setAccessToken } from "../auth/session.js";
import AuthFormLayout from "../components/AuthFormLayout.jsx";

export default function SignupPage() {
    const navigate = useNavigate();

    const [name, setName] =
        useState("");

    const birthYearRef =
        useRef(null);

    const birthMonthRef =
        useRef(null);

    const birthDayRef =
        useRef(null);

    const [birthYear, setBirthYear] =
        useState("");

    const [birthMonth, setBirthMonth] =
        useState("");

    const [birthDay, setBirthDay] =
        useState("");

    const birthDate =
        birthYear.length === 4
        && birthMonth.length === 2
        && birthDay.length === 2
            ? birthYear + "-" + birthMonth + "-" + birthDay
            : "";

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
                setLoginIdCheckStatus("idle");

                if (
                    trimmed.length < 1
                    || trimmed.length > 255
                ) {
                    setError(
                        "아이디는 255자 이하로 입력해주세요."
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

    function handleBirthYearChange(event) {
        const value =
            event.target.value
                .replace(/\D/g, "")
                .slice(0, 4);

        if (value.length === 4) {
            const year = Number(value);
            const currentYear = new Date().getFullYear();

            if (year < 1900 || year > currentYear) {
                setError(
                    "생년월일의 년도는 1900년부터 현재 년도까지 입력해주세요."
                );
                return;
            }

            setBirthYear(value);
            setError("");

            birthMonthRef.current?.focus();
            birthMonthRef.current?.select();
            return;
        }

        setBirthYear(value);
        setError("");
    }

    function handleBirthMonthChange(event) {
        const value =
            event.target.value
                .replace(/\D/g, "")
                .slice(0, 2);

        setBirthMonth(value);

        if (value.length === 2) {
            birthDayRef.current?.focus();
            birthDayRef.current?.select();
        }
    }

    function handleBirthDayChange(event) {
        setBirthDay(
            event.target.value
                .replace(/\D/g, "")
                .slice(0, 2)
        );
    }

    function handleLoginIdChange(event) {
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
        await checkLoginId(loginId);
    }

    function handleSubmit(event) {
        event.preventDefault();

        const year = Number(birthYear);
        const month = Number(birthMonth);
        const day = Number(birthDay);
        const currentYear = new Date().getFullYear();

        if (
            birthYear.length !== 4 ||
            year < 1900 ||
            year > currentYear ||
            birthMonth.length !== 2 ||
            month < 1 ||
            month > 12 ||
            birthDay.length !== 2 ||
            day < 1 ||
            day > new Date(year, month, 0).getDate()
        ) {
            setError("올바른 생년월일을 입력해주세요.");
            return;
        }

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

        if (password.length < 8) {
            setError(
                "비밀번호는 8자리 이상 입력해주세요."
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
                await authApi.signup({
                    name:
                        name.trim(),
                    birthDate,
                    loginId:
                        loginId.trim(),
                    password
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
                일반 회원가입
            </h2>

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
                    onChange={(event) =>
                        setName(
                            event.target.value
                        )
                    }
                    placeholder="이름"
                    required
                />

                <label htmlFor="signup-birth-date">
                    생년월일
                </label>

                <div className="birth-date-input-row">
                    <input
                        ref={birthYearRef}
                        type="text"
                        inputMode="numeric"
                        value={birthYear}
                        onChange={handleBirthYearChange}
                        placeholder="YYYY"
                        maxLength={4}
                        aria-label="생년월일 년도"
                        required
                    />

                    <span>-</span>

                    <input
                        ref={birthMonthRef}
                        type="text"
                        inputMode="numeric"
                        value={birthMonth}
                        onChange={handleBirthMonthChange}
                        placeholder="MM"
                        maxLength={2}
                        aria-label="생년월일 월"
                        required
                    />

                    <span>-</span>

                    <input
                        ref={birthDayRef}
                        type="text"
                        inputMode="numeric"
                        value={birthDay}
                        onChange={handleBirthDayChange}
                        placeholder="DD"
                        maxLength={2}
                        aria-label="생년월일 일"
                        required
                    />
                </div>

                <label htmlFor="signup-login-id">
                    아이디
                </label>

                <div className="login-id-row">
                    <input
                        id="signup-login-id"
                        type="text"
                        value={loginId}
                        onChange={
                            handleLoginIdChange
                        }
                        placeholder="아이디를 입력해주세요."
                        autoComplete="username"
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
                            사용 가능한 아이디입니다.
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
                            이미 사용 중인 아이디입니다.
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
                    minLength={8}
                    maxLength={100}
                    required
                />

                {
                    password && password.length < 8 && (
                        <p
                            className="error-message field-message"
                            role="alert"
                        >
                            비밀번호는 8자리 이상 입력해주세요.
                        </p>
                    )
                }

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