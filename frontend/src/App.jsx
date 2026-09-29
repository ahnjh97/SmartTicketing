import { useEffect, useState } from "react";
import "./index.css";
import ResidencePreference from "./components/ResidencePreference";

const API = "http://localhost:8080";

function App() {
    const [loading, setLoading] = useState(true);

    const [user, setUser] = useState(null);

    const [loginId, setLoginId] = useState("");
    const [password, setPassword] = useState("");

    const [signupName, setSignupName] = useState("");
    const [signupBirthDate, setSignupBirthDate] = useState("");
    const [signupLoginId, setSignupLoginId] = useState("");
    const [signupPassword, setSignupPassword] = useState("");

    const [nickname, setNickname] = useState("");
    const [showSignup, setShowSignup] = useState(false);

    const [message, setMessage] = useState("");
    const [error, setError] = useState("");

    const [kakaoSetupCompleted, setKakaoSetupCompleted] =
        useState(false);

    const [editingProfile, setEditingProfile] =
        useState(false);

    useEffect(() => {
        initialize();
    }, []);

    async function initialize() {
        try {
            const searchParams = new URLSearchParams(
                window.location.search
            );

            const oauthError = searchParams.get("error");

            if (oauthError) {
                setError(
                    decodeURIComponent(oauthError)
                );

                window.history.replaceState(
                    {},
                    document.title,
                    window.location.pathname
                );

                setLoading(false);
                return;
            }

            const hash = window.location.hash;

            if (hash.startsWith("#token=")) {
                const token = decodeURIComponent(
                    hash.substring("#token=".length)
                );

                localStorage.setItem(
                    "accessToken",
                    token
                );

                window.history.replaceState(
                    {},
                    document.title,
                    window.location.pathname
                );

                await loadMyInfo(token);

                return;
            }

            const token =
                localStorage.getItem("accessToken");

            if (!token) {
                setLoading(false);
                return;
            }

            await loadMyInfo(token);
        } catch (e) {
            console.error(e);

            setError(
                e.message ??
                "로그인 처리 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    async function loadMyInfo(
        token = localStorage.getItem("accessToken")
    ) {
        if (!token) {
            setUser(null);
            return;
        }

        const response = await fetch(
            `${API}/api/users/me`,
            {
                headers: {
                    Authorization:
                        `Bearer ${token}`,
                },
            }
        );

        const text =
            await response.text();

        if (!response.ok) {
            localStorage.removeItem("accessToken");

            throw new Error(
                `회원정보 조회 실패 (${response.status})`
            );
        }

        let data;

        try {
            data = JSON.parse(text);
        } catch {
            throw new Error(
                "회원정보 응답을 처리할 수 없습니다."
            );
        }

        setUser(data);

        setNickname(
            data.nickname ?? ""
        );

        /*
         * 카카오 최초 로그인 여부
         *
         * 카카오만 연결되어 있고
         * 이메일이 없는 경우
         * 최초 닉네임 설정 화면으로 이동
         */
        const isKakaoOnlyUser =
            data.linkedProviders?.length === 1 &&
            data.linkedProviders.includes("KAKAO") &&
            !data.email;

        const setupKey =
            `kakaoProfileSetupDone:${data.id}`;

        const setupDone =
            localStorage.getItem(setupKey) === "true";

        setKakaoSetupCompleted(
            !isKakaoOnlyUser || setupDone
        );
    }

    async function normalLogin(e) {
        e.preventDefault();

        setError("");
        setMessage("");

        try {
            const response =
                await fetch(
                    `${API}/api/auth/login`,
                    {
                        method: "POST",
                        headers: {
                            "Content-Type":
                                "application/json",
                        },
                        body: JSON.stringify({
                            loginId,
                            password,
                        }),
                    }
                );

            const text =
                await response.text();

            let data;

            try {
                data = JSON.parse(text);
            } catch {
                data = {};
            }

            if (!response.ok) {
                throw new Error(
                    data.message ??
                    "아이디 또는 비밀번호가 올바르지 않습니다."
                );
            }

            localStorage.setItem(
                "accessToken",
                data.accessToken
            );

            setLoginId("");
            setPassword("");

            await loadMyInfo(
                data.accessToken
            );
        } catch (e) {
            setError(
                e.message ??
                "로그인에 실패했습니다."
            );
        }
    }

    async function signup(e) {
        e.preventDefault();

        setError("");
        setMessage("");

        try {
            const response =
                await fetch(
                    `${API}/api/auth/signup`,
                    {
                        method: "POST",
                        headers: {
                            "Content-Type":
                                "application/json",
                        },
                        body: JSON.stringify({
                            name: signupName,
                            birthDate:
                            signupBirthDate,
                            loginId:
                            signupLoginId,
                            password:
                            signupPassword,
                        }),
                    }
                );

            const text =
                await response.text();

            let data;

            try {
                data = JSON.parse(text);
            } catch {
                data = {};
            }

            if (!response.ok) {
                throw new Error(
                    data.message ??
                    "회원가입에 실패했습니다."
                );
            }

            setMessage(
                "회원가입이 완료되었습니다. 로그인해주세요."
            );

            setShowSignup(false);

            setSignupName("");
            setSignupBirthDate("");
            setSignupLoginId("");
            setSignupPassword("");
        } catch (e) {
            setError(
                e.message ??
                "회원가입에 실패했습니다."
            );
        }
    }

    async function withdraw() {
        const confirmed =
            window.confirm(
                "정말 탈퇴하시겠습니까?\n탈퇴 후에는 다시 로그인할 수 없습니다."
            );

        if (!confirmed) {
            return;
        }

        setError("");
        setMessage("");

        try {
            const token =
                localStorage.getItem("accessToken");

            if (!token) {
                throw new Error(
                    "로그인 정보가 없습니다."
                );
            }

            const response =
                await fetch(
                    `${API}/api/users/me`,
                    {
                        method: "DELETE",
                        headers: {
                            Authorization:
                                `Bearer ${token}`,
                        },
                    }
                );

            if (!response.ok) {
                const text =
                    await response.text();

                let data = {};

                try {
                    data = JSON.parse(text);
                } catch {
                    data = {};
                }

                throw new Error(
                    data.message ??
                    `회원 탈퇴 실패 (${response.status})`
                );
            }

            localStorage.removeItem(
                "accessToken"
            );

            if (user?.id) {
                localStorage.removeItem(
                    `kakaoProfileSetupDone:${user.id}`
                );
            }

            setUser(null);
            setEditingProfile(false);
            setNickname("");
            setShowSignup(false);
            setKakaoSetupCompleted(false);

            setMessage(
                "회원 탈퇴가 완료되었습니다."
            );
        } catch (e) {
            setError(
                e.message ??
                "회원 탈퇴에 실패했습니다."
            );
        }
    }

    function socialLogin(provider) {
        window.location.href =
            `${API}/oauth2/authorization/${provider}`;
    }

    function handlePreferenceSaved(data) {
        setUser(data);
        setEditingProfile(false);

        setNickname(
            data.nickname ?? ""
        );

        setError("");
        setMessage(
            "회원정보가 수정되었습니다."
        );
    }

    function cancelProfileEdit() {
        setEditingProfile(false);
        setError("");
        setMessage("");
    }

    function logout() {
        localStorage.removeItem(
            "accessToken"
        );

        if (user?.id) {
            localStorage.removeItem(
                `kakaoProfileSetupDone:${user.id}`
            );
        }

        setUser(null);

        setNickname("");

        setMessage("");
        setError("");

        setKakaoSetupCompleted(false);
    }

    async function saveKakaoNickname(e) {
        e.preventDefault();

        setError("");
        setMessage("");

        const value =
            nickname.trim();

        if (!value) {
            setError(
                "닉네임을 입력해주세요."
            );
            return;
        }

        if (value.length > 100) {
            setError(
                "닉네임은 100자 이하로 입력해주세요."
            );
            return;
        }

        try {
            const token =
                localStorage.getItem(
                    "accessToken"
                );

            if (!token) {
                throw new Error(
                    "로그인 정보가 없습니다."
                );
            }

            const response =
                await fetch(
                    `${API}/api/users/me`,
                    {
                        method: "PATCH",
                        headers: {
                            "Content-Type":
                                "application/json",
                            Authorization:
                                `Bearer ${token}`,
                        },
                        body: JSON.stringify({
                            nickname: value,
                        }),
                    }
                );

            const text =
                await response.text();

            let data;

            try {
                data = JSON.parse(text);
            } catch {
                data = {};
            }

            if (!response.ok) {
                throw new Error(
                    data.message ??
                    "닉네임 저장에 실패했습니다."
                );
            }

            setUser(data);

            setNickname(
                data.nickname ?? value
            );

            localStorage.setItem(
                `kakaoProfileSetupDone:${data.id}`,
                "true"
            );

            setKakaoSetupCompleted(
                true
            );

            setMessage(
                "닉네임이 저장되었습니다."
            );
        } catch (e) {
            setError(
                e.message ??
                "닉네임 저장에 실패했습니다."
            );
        }
    }

    function handlePreferenceSaved(data) {
        setUser(data);

        setNickname(
            data.nickname ?? ""
        );

        setMessage(
            "회원 정보 및 선호 정보가 저장되었습니다."
        );
    }

    if (loading) {
        return (
            <div className="page">
                <div className="card loading-card">
                    <h1>
                        SmartTicketing
                    </h1>

                    <p>
                        불러오는 중...
                    </p>
                </div>
            </div>
        );
    }

    /*
     * 로그인하지 않은 상태
     */
    if (!user) {
        return (
            <div className="page">
                <div className="card auth-card">

                    <h1>
                        SmartTicketing
                    </h1>

                    <p className="subtitle">
                        영화 예매 시스템
                    </p>

                    {!showSignup ? (
                        <>
                            <div className="social-buttons">

                                <button
                                    type="button"
                                    className="social-button google"
                                    onClick={() =>
                                        socialLogin(
                                            "google"
                                        )
                                    }
                                >
                                    Google 로그인
                                </button>

                                <button
                                    type="button"
                                    className="social-button naver"
                                    onClick={() =>
                                        socialLogin(
                                            "naver"
                                        )
                                    }
                                >
                                    Naver 로그인
                                </button>

                                <button
                                    type="button"
                                    className="social-button kakao"
                                    onClick={() =>
                                        socialLogin(
                                            "kakao"
                                        )
                                    }
                                >
                                    Kakao 로그인
                                </button>

                            </div>

                            <div className="divider">
                                <span>
                                    또는
                                </span>
                            </div>

                            <form
                                onSubmit={
                                    normalLogin
                                }
                            >
                                <label>
                                    아이디
                                </label>

                                <input
                                    type="text"
                                    value={
                                        loginId
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setLoginId(
                                            e.target.value
                                        )
                                    }
                                    placeholder="아이디"
                                    autoComplete="username"
                                    required
                                />

                                <label>
                                    비밀번호
                                </label>

                                <input
                                    type="password"
                                    value={
                                        password
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setPassword(
                                            e.target.value
                                        )
                                    }
                                    placeholder="비밀번호"
                                    autoComplete="current-password"
                                    required
                                />

                                <button
                                    type="submit"
                                    className="primary-button"
                                >
                                    로그인
                                </button>
                            </form>

                            <button
                                type="button"
                                className="text-button"
                                onClick={() => {
                                    setShowSignup(
                                        true
                                    );

                                    setError("");
                                    setMessage("");
                                }}
                            >
                                일반 회원가입
                            </button>
                        </>
                    ) : (
                        <>
                            <h2>
                                일반 회원가입
                            </h2>

                            <form
                                onSubmit={
                                    signup
                                }
                            >
                                <label>
                                    이름
                                </label>

                                <input
                                    type="text"
                                    value={
                                        signupName
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setSignupName(
                                            e.target.value
                                        )
                                    }
                                    placeholder="이름"
                                    required
                                />

                                <label>
                                    생년월일
                                </label>

                                <input
                                    type="date"
                                    value={
                                        signupBirthDate
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setSignupBirthDate(
                                            e.target.value
                                        )
                                    }
                                    required
                                />

                                <label>
                                    아이디
                                </label>

                                <input
                                    type="text"
                                    value={
                                        signupLoginId
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setSignupLoginId(
                                            e.target.value
                                        )
                                    }
                                    placeholder="영문, 숫자, 밑줄 4~50자"
                                    autoComplete="username"
                                    required
                                />

                                <label>
                                    비밀번호
                                </label>

                                <input
                                    type="password"
                                    value={
                                        signupPassword
                                    }
                                    onChange={(
                                        e
                                    ) =>
                                        setSignupPassword(
                                            e.target.value
                                        )
                                    }
                                    placeholder="8~100자"
                                    autoComplete="new-password"
                                    required
                                />

                                <button
                                    type="submit"
                                    className="primary-button"
                                >
                                    회원가입
                                </button>
                            </form>

                            <button
                                type="button"
                                className="text-button"
                                onClick={() => {
                                    setShowSignup(
                                        false
                                    );

                                    setError("");
                                    setMessage("");
                                }}
                            >
                                로그인으로 돌아가기
                            </button>
                        </>
                    )}

                    {error && (
                        <p className="error-message">
                            {error}
                        </p>
                    )}

                    {message && (
                        <p className="success-message">
                            {message}
                        </p>
                    )}

                </div>
            </div>
        );
    }

    /*
     * 카카오 최초 로그인 사용자
     * → 닉네임 설정
     */
    if (!kakaoSetupCompleted) {
        return (
            <div className="page">
                <div className="card setup-card">

                    <div className="setup-badge">
                        KAKAO
                    </div>

                    <h1>
                        닉네임 설정
                    </h1>

                    <p className="subtitle">
                        SmartTicketing에서
                        사용할 닉네임을
                        설정해주세요.
                    </p>

                    <form
                        onSubmit={
                            saveKakaoNickname
                        }
                    >
                        <label>
                            닉네임
                        </label>

                        <input
                            type="text"
                            value={
                                nickname
                            }
                            onChange={(e) =>
                                setNickname(
                                    e.target.value
                                )
                            }
                            placeholder="닉네임을 입력해주세요."
                            maxLength={100}
                            autoFocus
                            required
                        />

                        <button
                            type="submit"
                            className="primary-button"
                        >
                            닉네임 저장
                        </button>
                    </form>

                    {error && (
                        <p className="error-message">
                            {error}
                        </p>
                    )}

                    {message && (
                        <p className="success-message">
                            {message}
                        </p>
                    )}

                </div>
            </div>
        );
    }

    /*
     * 로그인 후 회원정보 /
     * 거주지 / 주변 영화관 /
     * 선호 좌석 설정
     */
    return (
        <div className="page">
            <div className="card profile-card">

                <div className="profile-header">
                    <div>
                        <h1>
                            회원정보
                        </h1>

                        <p className="subtitle">
                            {user.nickname}
                        </p>
                    </div>

                    <button
                        type="button"
                        className="logout-button"
                        onClick={logout}
                    >
                        로그아웃
                    </button>
                </div>

                <div className="user-info">

                    <div>
                        <span>
                            이름
                        </span>

                        <strong>
                            {user.name}
                        </strong>
                    </div>

                    <div>
                        <span>
                            닉네임
                        </span>

                        <strong>
                            {user.nickname}
                        </strong>
                    </div>

                    <div>
                        <span>
                            아이디
                        </span>

                        <strong>
                            {user.loginId ??
                                "소셜 로그인"}
                        </strong>
                    </div>

                    <div>
                        <span>
                            이메일
                        </span>

                        <strong>
                            {user.email ??
                                "미등록"}
                        </strong>
                    </div>

                    <div>
                        <span>
                            생년월일
                        </span>

                        <strong>
                            {user.birthDate ??
                                "미등록"}
                        </strong>
                    </div>

                    <div>
                        <span>
                            로그인 연동
                        </span>

                        <strong>
                            {user.linkedProviders
                                ?.length
                                ? user.linkedProviders.join(
                                    ", "
                                )
                                : "일반 회원"}
                        </strong>
                    </div>

                </div>

                <ResidencePreference
                    user={user}
                    onSaved={
                        handlePreferenceSaved
                    }
                />

                {error && (
                    <p className="error-message">
                        {error}
                    </p>
                )}

                {message && (
                    <p className="success-message">
                        {message}
                    </p>
                )}

            </div>
        </div>
    );
}

export default App;