import { Navigate, Route, Routes } from "react-router-dom";
import AuthProvider from "./auth/AuthProvider.jsx";
import AppLayout from "./layouts/AppLayout.jsx";
import RouteGuard from "./components/RouteGuard.jsx";
import EmptyPage from "./pages/EmptyPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import SignupPage from "./pages/SignupPage.jsx";
import ProfilePage from "./pages/ProfilePage.jsx";
import PreferencesPage from "./pages/PreferencesPage.jsx";
import PreferenceSetupPage from "./pages/PreferenceSetupPage.jsx";
import OAuthCallbackPage from "./pages/OAuthCallbackPage.jsx";
import { PAGE_PATHS } from "./navigation.js";

export default function App() {
    return (
        <AuthProvider>
            <Routes>
                <Route element={<AppLayout />}>
                    <Route path={PAGE_PATHS.login} element={<LoginPage />} />
                    <Route path={PAGE_PATHS.signup} element={<SignupPage />} />
                    <Route path={PAGE_PATHS.callback} element={<OAuthCallbackPage />} />

                    <Route element={<RouteGuard />}>
                        <Route path={PAGE_PATHS.home} element={<EmptyPage />} />
                        <Route path={PAGE_PATHS.movies} element={<EmptyPage />} />
                        <Route path={PAGE_PATHS.theaters} element={<EmptyPage />} />
                        <Route path={PAGE_PATHS.profile} element={<ProfilePage />} />
                        <Route path={PAGE_PATHS.preferences} element={<PreferencesPage />} />
                        <Route path={PAGE_PATHS.preferenceSetup} element={<PreferenceSetupPage />} />
                        <Route path="*" element={<Navigate to={PAGE_PATHS.home} replace />} />
                    </Route>
                </Route>
            </Routes>
        </AuthProvider>
    );
}