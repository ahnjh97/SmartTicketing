import { Navigate } from "react-router-dom";
import { PAGE_PATHS, redirectPage } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import LoadingPage from "./LoadingPage.jsx";
import { bookingReturn } from '../booking/state.js';

export default function OAuthCallbackPage() {
    const {
        user,
        loading,
        preferenceSetupRequired
    } = useAuth();

    if (loading) {
        return <LoadingPage />;
    }

    const target = redirectPage({
        page: "callback",
        authenticated: Boolean(user),
        preferenceSetupRequired
    });

    return (
        <Navigate
            to={target === 'profile' ? bookingReturn() || PAGE_PATHS.profile : PAGE_PATHS[target]}
            replace
        />
    );
}
