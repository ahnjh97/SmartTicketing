import { Navigate, Route, Routes } from "react-router-dom";
import AuthProvider from "./auth/AuthProvider.jsx";
import AppLayout from "./layouts/AppLayout.jsx";
import RouteGuard from "./components/RouteGuard.jsx";
import EmptyPage from "./pages/EmptyPage.jsx";
import HomePage from "./pages/HomePage.jsx";
import BookingPage from "./pages/BookingPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import FindAccountPage from "./pages/FindAccountPage.jsx";
import SignupPage from "./pages/SignupPage.jsx";
import SocialSignupPage from "./pages/SocialSignupPage.jsx";
import ProfilePage from "./pages/ProfilePage.jsx";
import PreferencesPage from "./pages/PreferencesPage.jsx";
import PreferenceSetupPage from "./pages/PreferenceSetupPage.jsx";
import OAuthCallbackPage from "./pages/OAuthCallbackPage.jsx";
import BookingRestorePage from './pages/BookingRestorePage.jsx';
import TicketsPage from "./pages/TicketsPage.jsx";
import NotificationsPage from "./pages/NotificationsPage.jsx";
import TicketVerifyPage from "./pages/TicketVerifyPage.jsx";
import { PAGE_PATHS } from "./navigation.js";

export default function App() {
    return (
        <AuthProvider>
            <Routes>
                <Route path="/ticket/verify/:qrCode" element={<TicketVerifyPage />} />
                <Route element={<AppLayout />}>
                    <Route path={PAGE_PATHS.login} element={<LoginPage />} />
                    <Route path={PAGE_PATHS.findAccount} element={<FindAccountPage />} />
                    <Route path={PAGE_PATHS.signup} element={<SignupPage />} />
                    <Route path={PAGE_PATHS.socialSignup} element={<SocialSignupPage />} />
                    <Route path={PAGE_PATHS.callback} element={<OAuthCallbackPage />} />

                    <Route element={<RouteGuard />}>
                        <Route path={PAGE_PATHS.home} element={<HomePage />} />
                        <Route path={PAGE_PATHS.movies} element={<BookingPage key="movies" mode="movie" />} />
                        <Route path={PAGE_PATHS.theaters} element={<BookingPage key="theaters" mode="theater" />} />
                        <Route path={PAGE_PATHS.tickets} element={<TicketsPage />} />
                        <Route path={PAGE_PATHS.bookingRestore} element={<BookingRestorePage />} />
                        <Route path={PAGE_PATHS.notifications} element={<NotificationsPage />} />
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
