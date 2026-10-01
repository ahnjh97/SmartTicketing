import { Navigate, Outlet, useLocation } from "react-router-dom";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS, pageForPath, redirectPage } from "../navigation.js";
import LoadingPage from "../pages/LoadingPage.jsx";
import { useEffect } from 'react';
import { bookingReturn, rememberBooking } from '../booking/state.js';

export default function RouteGuard() {
    const { user, loading, preferenceSetupRequired } = useAuth();
    const location = useLocation();
    const target = loading ? null : redirectPage({
        page: pageForPath(location.pathname),
        authenticated: Boolean(user),
        preferenceSetupRequired,
    });
    useEffect(() => {
        if (target === 'preferenceSetup') rememberBooking(location.pathname + location.search);
    }, [target, location.pathname, location.search]);
    if (loading) return <LoadingPage />;
    if (target) return <Navigate to={target === 'profile' ? bookingReturn() || PAGE_PATHS.profile : PAGE_PATHS[target]} replace />;
    return <Outlet />;
}
