import { useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import useAsyncAction from "../hooks/useAsyncAction.js";
import { getSeatLabel } from "../utils/seatLabels.js";

export default function ProfilePage() {
    const { user, logout: logoutSession, withdraw: withdrawSession } = useAuth();
    const [message, setMessage] = useState(useLocation().state?.message ?? "");
    const { error, setError, pending, run } = useAsyncAction();
    const routeNavigate = useNavigate();
    const navigate = (page) => routeNavigate(PAGE_PATHS[page]);

    function renderSocialLogo(provider) {
        if (provider === "google") {
            return (
                <svg className="social-logo google-logo" viewBox="0 0 74 24" aria-label="Google">
                    <path fill="#4285F4" d="M23.2 12.2c0-.8-.1-1.6-.2-2.3H12v4.3h6.3c-.3 1.4-1.1 2.6-2.3 3.4v2.8h3.7c2.2-2 3.5-4.8 3.5-8.2Z"/>
                    <path fill="#34A853" d="M12 24c3.2 0 5.9-1.1 7.8-3l-3.7-2.8c-1 .7-2.4 1.1-4.1 1.1-3.1 0-5.7-2.1-6.6-5h-3.8v2.9C3.5 21.2 7.4 24 12 24Z"/>
                    <path fill="#FBBC05" d="M5.4 14.3c-.2-.7-.4-1.5-.4-2.3s.1-1.6.4-2.3V6.8H1.6C1 8.3.6 10.1.6 12s.4 3.7 1 5.2l3.8-2.9Z"/>
                    <path fill="#EA4335" d="M12 4.7c1.8 0 3.4.6 4.7 1.8l3.5-3.5C17.9 1.1 15.2 0 12 0 7.4 0 3.5 2.8 1.6 6.8l3.8 2.9c.9-2.9 3.5-5 6.6-5Z"/>
                    <path fill="#4285F4" d="M30 18.2c-1.7 0-3.1-1.2-3.1-3.1s1.4-3.1 3.1-3.1c1.7 0 3.1 1.2 3.1 3.1s-1.4 3.1-3.1 3.1Zm0-8.8c-3.3 0-5.7 2.4-5.7 5.7s2.4 5.7 5.7 5.7 5.7-2.4 5.7-5.7-2.4-5.7-5.7-5.7Zm9.1 8.8c-1.7 0-3.1-1.2-3.1-3.1s1.4-3.1 3.1-3.1 3.1 1.2 3.1 3.1-1.4 3.1-3.1 3.1Zm0-8.8c-3.3 0-5.7 2.4-5.7 5.7s2.4 5.7 5.7 5.7 5.7-2.4 5.7-5.7-2.4-5.7-5.7-5.7Zm9.4 1.8v-1.5h-2.5v11.1c0 2.2-1.1 3.4-3.3 3.4v2.3c3.8 0 5.9-2.1 5.9-5.7V9.2h-.1Z"/>
                </svg>
            );
        }
        if (provider === "naver") {
            return (
                <svg className="social-logo naver-logo" viewBox="0 0 24 24" aria-label="Naver">
                    <path fill="currentColor" d="M4 4h5.1l5.8 8V4H20v16h-5.1L9 12v8H4V4Z"/>
                </svg>
            );
        }
        return (
            <svg className="social-logo kakao-logo" viewBox="0 0 24 24" aria-label="Kakao">
                <path fill="currentColor" d="M12 4C7 4 3 7.1 3 11.1c0 2.5 1.7 4.7 4.2 5.9L6.2 20.5c-.1.3.2.5.5.3l4-2.5c.4.1.9.1 1.3.1 5 0 9-3.1 9-7.1S17 4 12 4Z"/>
            </svg>
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
        <div className="page">
            <div className="card profile-card">
                <div className="profile-header">
                    <div><h1>회원정보</h1></div>
                    <button type="button" disabled={pending} className="logout-button" onClick={logout}>
                        로그아웃
                    </button>
                </div>

                <div className="user-info">
                    <div><span>이름</span><strong>{user.name}</strong></div>
                    <div><span>아이디</span><strong>{user.loginId ?? "소셜 로그인"}</strong></div>
                    <div><span>생년월일</span><strong>{user.birthDate ?? "미등록"}</strong></div>
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
                    <div>
                        <span>선호 영화관</span>
                        <div>
                            {user.preferredTheaters?.length ? user.preferredTheaters.map((theater) => (
                                <div key={theater.theaterId} style={{ marginBottom: "6px" }}>
                                    <strong>{theater.priority}위</strong>{" "}
                                    {theater.theaterName} ({theater.brand})
                                </div>
                            )) : <span>미설정</span>}
                        </div>
                    </div>
                    <div>
                        <span>선호 좌석</span>
                        <div>
                            {user.preferredSeats?.length ? user.preferredSeats.map((seat, index) => {
                                const position = typeof seat === "string" ? seat : seat.position;
                                const priority = typeof seat === "string" ? index + 1 : seat.priority ?? index + 1;
                                return (
                                    <div key={`${position}-${priority}-${index}`} style={{ marginBottom: "6px" }}>
                                        <strong>{priority}위</strong>{" "}{getSeatLabel(position)}
                                    </div>
                                );
                            }) : <span>미설정</span>}
                        </div>
                    </div>
                </div>

                <div className="profile-action-area">
                    <button type="button" disabled={pending} className="primary-button" onClick={() => {
                        navigate("preferences");
                        setError("");
                        setMessage("");
                    }}>
                        회원정보 수정
                    </button>
                    <button type="button" disabled={pending} className="withdraw-button" onClick={withdraw}>
                        회원 탈퇴
                    </button>
                </div>

                {error && <p className="error-message">{error}</p>}
                {message && <p className="success-message">{message}</p>}
            </div>
        </div>
    );
}
