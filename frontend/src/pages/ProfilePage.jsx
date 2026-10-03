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
                <svg className="social-logo" viewBox="0 0 24 24" aria-hidden="true">
                    <path fill="#4285F4" d="M21.35 12.27c0-.71-.06-1.4-.18-2.05H12v3.88h5.24a4.48 4.48 0 0 1-1.94 2.94v2.45h3.14c1.84-1.69 2.91-4.18 2.91-7.22Z" />
                    <path fill="#34A853" d="M12 21.75c2.63 0 4.84-.87 6.45-2.36l-3.14-2.45c-.87.58-1.98.92-3.31.92-2.54 0-4.69-1.72-5.46-4.03H3.3v2.53A9.75 9.75 0 0 0 12 21.75Z" />
                    <path fill="#FBBC05" d="M6.54 13.83A5.86 5.86 0 0 1 6.24 12c0-.64.11-1.26.3-1.83V7.64H3.3A9.74 9.74 0 0 0 2.25 12c0 1.57.38 3.06 1.05 4.36l3.24-2.53Z" />
                    <path fill="#EA4335" d="M12 6.14c1.43 0 2.71.49 3.72 1.45l2.79-2.79C16.84 3.22 14.63 2.25 12 2.25a9.75 9.75 0 0 0-8.7 5.39l3.24 2.53c.77-2.31 2.92-4.03 5.46-4.03Z" />
                </svg>
            );
        }
        if (provider === "naver") {
            return (
                <svg className="social-logo" viewBox="0 0 24 24" aria-hidden="true">
                    <path fill="currentColor" d="M4 4h5.08l5.92 8.03V4H20v16h-5.08L9 11.97V20H4V4Z" />
                </svg>
            );
        }
        return (
            <svg className="social-logo" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M12 4C7.03 4 3 7.14 3 11.02c0 2.5 1.65 4.7 4.16 5.94L6.2 20.5c-.08.28.23.5.48.34l4.04-2.55c.42.06.85.09 1.28.09 4.97 0 9-3.14 9-7.02S16.97 4 12 4Z" />
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
