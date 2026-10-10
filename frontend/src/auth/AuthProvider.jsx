import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { authApi } from "../api/auth.js";
import { userApi } from "../api/users.js";
import { ApiError } from "../api/client.js";
import { AuthContext } from "./AuthContext.js";
import { clearBookingSession } from "../booking/state.js";
import { restoreSession } from "./bootstrap.js";
import {
    getAccessToken,
    setAccessToken,
    clearAccessToken,
    subscribeToSessionExpiration,
    needsPreferenceSetup,
} from "./session.js";

export default function AuthProvider({ children }) {
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);
    const [sessionError, setSessionError] = useState("");
    const initialUrl = useRef(window.location.href);
    const bootstrap = useRef(null);
    const location = useLocation();
    const navigate = useNavigate();

    useEffect(() => {
        let active = true;

        const unsubscribe =
            subscribeToSessionExpiration(() => {
                clearBookingSession();
                setUser(null);
                setSessionError(
                    "로그인이 만료되었습니다. 다시 로그인해주세요."
                );
            });

        bootstrap.current ??=
            restoreSession(initialUrl.current);

        bootstrap.current
            .then(({ user: restoredUser, token }) => {
                if (active) {
                    setUser(
                        getAccessToken() === token
                            ? restoredUser
                            : null
                    );
                }
            })
            .catch((error) => {
                if (active) {
                    setSessionError(error.message);
                }
            })
            .finally(() => {
                if (active) {
                    setLoading(false);
                }
            });

        return () => {
            active = false;
            unsubscribe();
        };
    }, []);

    useEffect(() => {
        if (
            location.pathname === "/oauth2/callback"
            && (location.hash || location.search)
        ) {
            navigate(
                location.pathname,
                { replace: true }
            );
        }
    }, [
        location.pathname,
        location.hash,
        location.search,
        navigate
    ]);

    const login = useCallback(
        async (credentials) => {
            setSessionError("");

            const response =
                await authApi.login(credentials);

            if (!response?.accessToken) {
                throw new ApiError(
                    "로그인 응답을 처리할 수 없습니다.",
                    502
                );
            }

            setAccessToken(
                response.accessToken
            );

            const data =
                await userApi.me(
                    response.accessToken
                );

            if (
                getAccessToken()
                !== response.accessToken
            ) {
                throw new ApiError(
                    "로그인이 만료되었습니다.",
                    401
                );
            }

            setUser(data);

            return data;
        },
        []
    );

    const logout = useCallback(
        async () => {
            try {
                await authApi.logout();
            } finally {
                clearBookingSession();
                clearAccessToken();
                setUser(null);
                setSessionError("");
                navigate("/login", { replace: true });
            }
        },
        [navigate]
    );

    const withdraw = useCallback(
        async () => {
            await userApi.withdraw();

            clearBookingSession();
            clearAccessToken();
            setUser(null);
            setSessionError("");
            navigate("/login", {
                replace: true,
                state: { message: "회원 탈퇴가 완료되었습니다." },
            });
        },
        [navigate]
    );

    const updateUser = useCallback(
        (data) => {
            if (
                getAccessToken()
                && data.id === user?.id
            ) {
                setUser(data);
            }
        },
        [user?.id]
    );

    return (
        <AuthContext.Provider
            value={{
                user,
                loading,
                sessionError,
                login,
                logout,
                withdraw,
                updateUser,
                preferenceSetupRequired:
                    needsPreferenceSetup(user),
            }}
        >
            {children}
        </AuthContext.Provider>
    );
}
