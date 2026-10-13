import PostAuthRedirect from "./PostAuthRedirect.jsx";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS, pageForPath, redirectPage } from "../navigation.js";
import LoadingPage from "../pages/LoadingPage.jsx";
import { useEffect } from 'react';
import { rememberBooking } from '../booking/state.js';

export default function RouteGuard() {
    const { user, loading, preferenceSetupRequired, redirectingToLogin } = useAuth();
    const location = useLocation();
    const target = loading ? null : redirectPage({
        page: pageForPath(location.pathname),
        authenticated: Boolean(user),
        preferenceSetupRequired,
    });
    useEffect(() => {
        if (!redirectingToLogin && (target === 'preferenceSetup' || target === 'login')) rememberBooking(location.pathname + location.search);
    }, [target, location.pathname, location.search, redirectingToLogin]);
    if (redirectingToLogin && location.pathname !== PAGE_PATHS.login) return <Navigate to={PAGE_PATHS.login} replace />;
    if (loading) return <LoadingPage />;
    if (target === 'profile') return <PostAuthRedirect />;
    if (target) return <Navigate to={PAGE_PATHS[target]} replace />;
    return <Outlet />;
}
