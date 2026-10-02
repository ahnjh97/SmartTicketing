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
                    <div>
                        <span className="profile-eyebrow">MY PAGE</span>
                        <h1>회원정보</h1>
                        <p className="profile-description">
                            내 계정 정보와 영화관 · 좌석 선호 설정을 한눈에 확인하세요.
                        </p>
                    </div>

                    <button
                        type="button"
                        disabled={pending}
                        className="logout-button"
                        onClick={logout}
                    >
                        로그아웃
                    </button>
                </div>

                <div className="profile-overview">
                    <div className="profile-overview-main">
                        <div className="profile-avatar">
                            {(user.name ?? "?").charAt(0)}
                        </div>
                        <div>
                            <span className="profile-overview-label">ACCOUNT</span>
                            <strong>{user.name ?? "회원"}</strong>
                            <p>{user.loginId ?? "소셜 로그인 계정"}</p>
                        </div>
                    </div>

                    <div className="profile-overview-meta">
                        <div>
                            <span>로그인 방식</span>
                            <strong>
                                {user.linkedProviders?.length
                                    ? user.linkedProviders.join(", ")
                                    : "일반 회원"}
                            </strong>
                        </div>
                        <div>
                            <span>생년월일</span>
                            <strong>{user.birthDate ?? "미등록"}</strong>
                        </div>
                    </div>
                </div>

                <div className="profile-section-heading">
                    <div>
                        <span>ACCOUNT INFO</span>
                        <h2>기본 정보</h2>
                    </div>
                </div>

                <div className="user-info profile-basic-info">
                    <div>
                        <span>이름</span>
                        <strong>{user.name ?? "미등록"}</strong>
                    </div>
                    <div>
                        <span>아이디</span>
                        <strong>{user.loginId ?? "소셜 로그인"}</strong>
                    </div>
                    <div>
                        <span>생년월일</span>
                        <strong>{user.birthDate ?? "미등록"}</strong>
                    </div>
                    <div>
                        <span>로그인 연동</span>
                        <strong>
                            {user.linkedProviders?.length
                                ? user.linkedProviders.join(", ")
                                : "일반 회원"}
                        </strong>
                    </div>
                </div>

                <div className="profile-preference-grid">
                    <section className="profile-info-panel">
                        <div className="profile-section-heading">
                            <div>
                                <span>PREFERRED THEATERS</span>
                                <h2>선호 영화관</h2>
                            </div>
                            <em>{user.preferredTheaters?.length ?? 0}곳</em>
                        </div>

                        <div className="profile-preference-list">
                            {user.preferredTheaters?.length ? (
                                user.preferredTheaters.map((theater) => (
                                    <div className="profile-preference-item" key={theater.theaterId}>
                                        <b>{theater.priority}위</b>
                                        <div>
                                            <strong>{theater.theaterName}</strong>
                                            <span>{theater.brand}</span>
                                        </div>
                                    </div>
                                ))
                            ) : (
                                <div className="profile-empty">선호 영화관이 아직 설정되지 않았습니다.</div>
                            )}
                        </div>
                    </section>

                    <section className="profile-info-panel">
                        <div className="profile-section-heading">
                            <div>
                                <span>PREFERRED SEATS</span>
                                <h2>선호 좌석</h2>
                            </div>
                            <em>{user.preferredSeats?.length ?? 0}개</em>
                        </div>

                        <div className="profile-preference-list">
                            {user.preferredSeats?.length ? (
                                user.preferredSeats.map((seat, index) => {
                                    const position =
                                        typeof seat === "string"
                                            ? seat
                                            : seat.position;

                                    const priority =
                                        typeof seat === "string"
                                            ? index + 1
                                            : seat.priority ?? index + 1;

                                    return (
                                        <div
                                            className="profile-preference-item"
                                            key={`${position}-${priority}-${index}`}
                                        >
                                            <b>{priority}위</b>
                                            <div>
                                                <strong>{getSeatLabel(position)}</strong>
                                                <span>추천 좌석 위치</span>
                                            </div>
                                        </div>
                                    );
                                })
                            ) : (
                                <div className="profile-empty">선호 좌석이 아직 설정되지 않았습니다.</div>
                            )}
                        </div>
                    </section>
                </div>

                <div className="profile-action-area profile-page-actions">
                    <button
                        type="button"
                        disabled={pending}
                        className="primary-button"
                        onClick={() => {
                            navigate("preferences");
                            setError("");
                            setMessage("");
                        }}
                    >
                        회원정보 수정
                    </button>

                    <button
                        type="button"
                        disabled={pending}
                        className="withdraw-button"
                        onClick={withdraw}
                    >
                        회원 탈퇴
                    </button>
                </div>

                {error && <p className="error-message">{error}</p>}
                {message && <p className="success-message">{message}</p>}
            </div>
        </div>
    );
}
