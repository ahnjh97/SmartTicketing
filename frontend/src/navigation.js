export const PAGE_PATHS = {
    adminData: "/admin/data",
    home: "/",
    movies: "/movies",
    theaters: "/theaters",
    tickets: "/tickets",
    bookingRestore: "/booking/restore",
    notifications: "/notifications",
    login: "/login",
    signup: "/signup",
    findAccount: "/find-account",
    socialSignup: "/signup/social",
    profile: "/profile",
    preferences: "/preferences",
    preferenceSetup: "/setup/preferences",
    callback: "/oauth2/callback",
};

const PROTECTED_PAGES = new Set([
    "adminData",
    "bookingRestore",
    "tickets",
    "notifications",
    "profile",
    "preferences",
    "preferenceSetup",
    "callback",
]);

export function pageForPath(pathname) {
    const path =
        pathname.replace(/\/+$/, "") || "/";

    return (
        Object.keys(PAGE_PATHS).find(
            (page) => PAGE_PATHS[page] === path
        ) ?? "home"
    );
}

export function redirectPage({
                                 page,
                                 authenticated,
                                 preferenceSetupRequired
                             }) {
    if (!authenticated) {
        return PROTECTED_PAGES.has(page)
            ? "login"
            : null;
    }

    if (preferenceSetupRequired) {
        return page === "preferenceSetup"
            ? null
            : "preferenceSetup";
    }

    if (
        [
            "login",
            "signup",
            "socialSignup",
            "callback",
            "preferenceSetup"
        ].includes(page)
    ) {
        return "profile";
    }

    return null;
}
