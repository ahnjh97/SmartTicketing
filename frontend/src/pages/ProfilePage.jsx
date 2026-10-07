import { formatShowDate } from '../utils/showtimeFormat.js';
import { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import SeatPreferenceMap from "../components/SeatPreferenceMap.jsx";
import GlassButton from "../components/GlassButton.jsx";
import styles from "./ProfilePage.module.css";

export default function ProfilePage() {
    const { user, logout: logoutSession, withdraw: withdrawSession } = useAuth();
    const [message, setMessage] = useState(useLocation().state?.message ?? "");
    const { error, setError, pending, run } = useAsyncAction();
    const routeNavigate = useNavigate();
    const navigate = (page) => routeNavigate(PAGE_PATHS[page]);

    function renderSocialLogo(provider) {
        const logos = {
            google: {
                src: "https://raw.githubusercontent.com/ahnjh97/SmartTicketing/main/Google_2015_logo.svg",
                alt: "Google",
                className: "social-logo social-wordmark google-logo",
            },
            naver: {
                src: "https://raw.githubusercontent.com/ahnjh97/SmartTicketing/main/Naver_Logotype.svg",
                alt: "NAVER",
                className: "social-logo social-wordmark naver-logo",
            },
            kakao: {
                src: "https://raw.githubusercontent.com/ahnjh97/SmartTicketing/main/Kakao_CI_yellow.svg",
                alt: "Kakao",
                className: "social-logo social-wordmark kakao-logo",
            },
        };

        const logo = logos[provider];
        if (!logo) return null;

        return (
            <img
                src={logo.src}
                alt={logo.alt}
                className={logo.className}
            />
        );
    }

    async function logout() {
        try { await logoutSession(); }
        catch (error) { console.error("로그아웃 요청 실패", error); }
        finally { routeNavigate(PAGE_PATHS.login, { replace: true }); }
    }

    function withdraw() {
        if (!window.confirm("정말 탈퇴하시겠습니까?\n탈퇴 후에는 다시 로그인할 수 없습니다.")) return;
        setMessage("");
        run(async () => {
            await withdrawSession();
            routeNavigate(PAGE_PATHS.login, {
                replace: true,
                state: { message: "회원 탈퇴가 완료되었습니다." },
            });
        });
    }

    return (
        <div className={styles.page}>
            <div>
                <div className={styles.header}>
                    <div><h1>회원정보</h1></div>
                    <GlassButton disabled={pending} onClick={logout}>
                        로그아웃
                    </GlassButton>
                </div>

                <div className={styles.info}>
                    <div><span>이름</span><strong>{user.name}</strong></div>
                    <div><span>아이디</span><strong>{user.loginId ?? "소셜 로그인"}</strong></div>
                    <div><span>생년월일</span><strong>{user.birthDate ? formatShowDate(user.birthDate) : "미등록"}</strong></div>
                    <div>
                        <span>로그인 연동</span>
                        {user.linkedProviders?.length ? (
                            <div className="profile-social-providers" aria-label="연동된 소셜 계정">
                                {user.linkedProviders.map((provider) => {
                                    const normalizedProvider = String(provider).toLowerCase();
                                    return (
                                        <span
                                            key={normalizedProvider}
                                            className={"profile-social-provider " + normalizedProvider}
                                            title={normalizedProvider.toUpperCase()}
                                        >
                                            {renderSocialLogo(normalizedProvider)}
                                        </span>
                                    );
                                })}
                            </div>
                        ) : <strong>일반 회원</strong>}
                    </div>
                    <div className={styles.preferences}>
                        <h2>선호 영화관</h2>
                        {user.preferredTheaters?.length ? (
                            <ol className={styles.theaters}>
                                {user.preferredTheaters.map((theater, index) => (
                                    <li key={theater.theaterId} className={styles.theater}>
                                        <span className={styles.theaterRank}>
                                            <strong>{theater.priority ?? index + 1}</strong><small>위</small>
                                        </span>
                                        <span className={styles.theaterName}>{theater.theaterName}</span>
                                    </li>
                                ))}
                            </ol>
                        ) : <p className={styles.empty}>미설정</p>}
                    </div>
                    <div className={styles.preferences}>
                        <h2>선호 좌석</h2>
                        {user.preferredSeats?.length
                            ? <SeatPreferenceMap seats={user.preferredSeats} />
                            : <p className={styles.empty}>미설정</p>}
                    </div>
                </div>

                <div className={styles.actions}>
                    <button type="button" disabled={pending} className={styles.withdraw} onClick={withdraw}>
                        회원 탈퇴
                    </button>
                    <GlassButton disabled={pending} onClick={() => {
                        navigate("preferences");
                        setError("");
                        setMessage("");
                    }}>
                        회원정보 수정
                    </GlassButton>
                </div>

                {error && <p className="error-message">{error}</p>}
                {message && <p className="success-message">{message}</p>}
            </div>
        </div>
    );
}
