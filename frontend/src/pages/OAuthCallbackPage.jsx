import { Navigate } from "react-router-dom";
import { PAGE_PATHS, redirectPage } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import LoadingPage from "./LoadingPage.jsx";

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
            to={PAGE_PATHS[target]}
            replace
        />
    );
}