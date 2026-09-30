import { Navigate, Outlet, useLocation } from "react-router-dom";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS, pageForPath, redirectPage } from "../navigation.js";
import LoadingPage from "../pages/LoadingPage.jsx";

export default function RouteGuard() {
    const { user, loading, preferenceSetupRequired } = useAuth();
    const location = useLocation();
    if (loading) return <LoadingPage />;
    const target = redirectPage({
        page: pageForPath(location.pathname),
        authenticated: Boolean(user),
        preferenceSetupRequired,
    });
    if (target) return <Navigate to={PAGE_PATHS[target]} replace />;
    return <Outlet />;
}
