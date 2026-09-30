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
            routeNavigate(PAGE_PATHS.login, { replace: true, state: { message: "회원 탈퇴가 완료되었습니다." } });
        });
    }
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
                        type="button" disabled={pending}
                        className="logout-button"
                        onClick={
                            logout
                        }
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

                    <div>
                        <span>
                            거주지
                        </span>

                        <strong>
                            {user.address ??
                                "미등록"}
                        </strong>
                    </div>

                    <div>
                        <span>
                            선호 영화관
                        </span>

                        <div>
                            {user.preferredTheaters
                                ?.length ? (
                                user.preferredTheaters.map(
                                    (
                                        theater
                                    ) => (
                                        <div
                                            key={
                                                theater.theaterId
                                            }
                                            style={{
                                                marginBottom:
                                                    "6px",
                                            }}
                                        >
                                            <strong>
                                                {
                                                    theater.priority
                                                }
                                                위
                                            </strong>
                                            {" "}
                                            {
                                                theater.theaterName
                                            }
                                            {" ("}
                                            {
                                                theater.brand
                                            }
                                            {")"}
                                        </div>
                                    )
                                )
                            ) : (
                                <span>
                                    미설정
                                </span>
                            )}
                        </div>
                    </div>

                    <div>
                        <span>
                            선호 좌석
                        </span>

                        <div>
                            {user.preferredSeats
                                ?.length ? (
                                user.preferredSeats.map(
                                    (
                                        seat,
                                        index
                                    ) => {
                                        const position =
                                            typeof seat ===
                                            "string"
                                                ? seat
                                                : seat.position;

                                        const priority =
                                            typeof seat ===
                                            "string"
                                                ? index +
                                                1
                                                : seat.priority ??
                                                index +
                                                1;

                                        return (
                                            <div
                                                key={`${position}-${priority}-${index}`}
                                                style={{
                                                    marginBottom:
                                                        "6px",
                                                }}
                                            >
                                                <strong>
                                                    {
                                                        priority
                                                    }
                                                    위
                                                </strong>
                                                {" "}
                                                {
                                                    getSeatLabel(
                                                        position
                                                    )
                                                }
                                            </div>
                                        );
                                    }
                                )
                            ) : (
                                <span>
                                    미설정
                                </span>
                            )}
                        </div>
                    </div>

                </div>

                <div
                    style={{
                        display: "flex",
                        gap: "10px",
                        marginTop: "20px",
                        flexWrap: "wrap",
                    }}
                >
                    <button
                        type="button" disabled={pending}
                        className="primary-button"
                        style={{
                            width: "auto",
                            marginTop: 0,
                            flex: 1,
                        }}
                        onClick={() => {
                            navigate("preferences");

                            setError("");
                            setMessage("");
                        }}
                    >
                        회원정보 수정
                    </button>

                    <button
                        type="button" disabled={pending}
                        className="logout-button"
                        style={{
                            flex: 1,
                            minHeight:
                                "46px",
                        }}
                        onClick={
                            withdraw
                        }
                    >
                        회원 탈퇴
                    </button>
                </div>

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
