import { Outlet, useLocation, useNavigate } from "react-router-dom";
import CommonHeader from "../components/CommonHeader.jsx";
import useAuth from "../hooks/useAuth.js";
import { PAGE_PATHS } from "../navigation.js";

export default function AppLayout() {
    const { user, loading, nicknameSetupRequired, preferenceSetupRequired, logout } = useAuth();
    const location = useLocation();
    const navigate = useNavigate();
    const empty = [PAGE_PATHS.home, PAGE_PATHS.movies, PAGE_PATHS.theaters].includes(location.pathname);

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
        <div className="app-layout">
            <CommonHeader
                user={user}
                disabled={loading}
                setupRequired={nicknameSetupRequired || preferenceSetupRequired}
                onLogout={handleLogout}
            />
            <main className={empty && !loading ? "app-shell-empty" : undefined}>
                <Outlet />
            </main>
        </div>
    );
}
