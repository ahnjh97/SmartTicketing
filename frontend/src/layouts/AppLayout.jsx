import { Outlet, useLocation } from "react-router-dom";
import CommonHeader from "../components/CommonHeader.jsx";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS } from "../navigation.js";
import styles from "./AppLayout.module.css";

export default function AppLayout() {
    const { user, loading, preferenceSetupRequired } = useAuth();
    const location = useLocation();
    const booking = [PAGE_PATHS.home, PAGE_PATHS.movies, PAGE_PATHS.theaters].includes(location.pathname);

    return (
        <div className={`app-layout${booking ? ' ' + styles.booking : ''}`}>
            <CommonHeader
                user={user}
                disabled={loading}
                setupRequired={preferenceSetupRequired}
            />
            <main>
                <Outlet />
            </main>
        </div>
    );
}
