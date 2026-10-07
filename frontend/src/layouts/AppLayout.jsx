import { Outlet } from "react-router-dom";
import CommonHeader from "../components/CommonHeader.jsx";
import useAuth from "../hooks/useAuth.js";
import styles from "./AppLayout.module.css";

export default function AppLayout() {
    const { user, loading, preferenceSetupRequired } = useAuth();

    return (
        <div className={`app-layout ${styles.layout}`}>
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
