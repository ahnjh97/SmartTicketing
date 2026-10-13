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
    const [logoutLocationKey, setLogoutLocationKey] = useState(null);
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);
    const [sessionError, setSessionError] = useState("");
    const initialUrl = useRef(window.location.href);
    const bootstrap = useRef(null);
    const location = useLocation();
    const navigate = useNavigate();

    const redirectingToLogin = logoutLocationKey === location.key && location.pathname !== '/login';

    useEffect(() => {
        if (!loading && !user && ['/', '/movies', '/theaters'].includes(location.pathname)) {
            clearBookingSession();
        }
    }, [loading, user, location.pathname]);

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

    const completeAuthentication = useCallback(async (response) => {
        setSessionError("");
        if (!response?.accessToken) throw new ApiError("인증 응답을 처리할 수 없습니다.", 502);
        setAccessToken(response.accessToken);
        try {
            const data = await userApi.me(response.accessToken);
            if (getAccessToken() !== response.accessToken) throw new ApiError("로그인이 만료되었습니다.", 401);
            setUser(data);
            navigate('/oauth2/callback', { replace: true });
            return data;
        } catch (error) {
            if (getAccessToken() === response.accessToken) clearAccessToken();
            setUser(null);
            throw error;
        }
    }, [navigate]);

    const login = useCallback(async credentials => {
        return completeAuthentication(await authApi.login(credentials));
    }, [completeAuthentication]);

    const logout = useCallback(
        async () => {
            try {
                await authApi.logout();
            } finally {
                setLogoutLocationKey(location.key);
                clearBookingSession();
                clearAccessToken();
                setUser(null);
                setSessionError("");
                navigate("/login", { replace: true });
            }
        },
        [navigate, location.key]
    );

    const withdraw = useCallback(
        async () => {
            await userApi.withdraw();

            setLogoutLocationKey(location.key);
            clearBookingSession();
            clearAccessToken();
            setUser(null);
            setSessionError("");
            navigate("/login", {
                replace: true,
                state: { message: "회원 탈퇴가 완료되었습니다." },
            });
        },
        [navigate, location.key]
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
                completeAuthentication,
                redirectingToLogin,
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
