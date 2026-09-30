export const PAGE_PATHS = {
    home: "/",
    movies: "/movies",
    theaters: "/theaters",
    tickets: "/tickets",
    login: "/login",
    signup: "/signup",
    socialSignup: "/signup/social",
    profile: "/profile",
    preferences: "/preferences",
    preferenceSetup: "/setup/preferences",
    callback: "/oauth2/callback",
};

const PROTECTED_PAGES = new Set([
    "tickets",
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
