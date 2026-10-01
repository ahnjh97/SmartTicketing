import { Outlet, useLocation, useNavigate } from "react-router-dom";
import CommonHeader from "../components/CommonHeader.jsx";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS } from "../navigation.js";
import styles from "./AppLayout.module.css";

export default function AppLayout() {
    const { user, loading, preferenceSetupRequired, logout } = useAuth();
    const location = useLocation();
    const navigate = useNavigate();
    const booking = [PAGE_PATHS.home, PAGE_PATHS.movies, PAGE_PATHS.theaters].includes(location.pathname);

    async function handleLogout() {
        try {
            await logout();
        } catch (error) {
            console.error("로그아웃 요청 실패", error);
        } finally {
            navigate(PAGE_PATHS.login, { replace: true });
        }
    }

    return (
        <div className={`app-layout${booking ? ' ' + styles.booking : ''}`}>
            <CommonHeader
                user={user}
                disabled={loading}
                setupRequired={preferenceSetupRequired}
                onLogout={handleLogout}
            />
            <main>
                <Outlet />
            </main>
        </div>
    );
}
