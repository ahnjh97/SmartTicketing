export const PAGE_PATHS = {
    home: "/",
    movies: "/movies",
    theaters: "/theaters",
    login: "/login",
    signup: "/signup",
    profile: "/profile",
    preferences: "/preferences",
    nicknameSetup: "/setup/nickname",
    preferenceSetup: "/setup/preferences",
    callback: "/oauth2/callback",
};

const PROTECTED_PAGES = new Set(["profile", "preferences", "nicknameSetup", "preferenceSetup", "callback"]);

export function pageForPath(pathname) {
    const path = pathname.replace(/\/+$/, "") || "/";
    return Object.keys(PAGE_PATHS).find((page) => PAGE_PATHS[page] === path) ?? "home";
}

export function redirectPage({ page, authenticated, nicknameSetupRequired, preferenceSetupRequired }) {
    if (!authenticated) return PROTECTED_PAGES.has(page) ? "login" : null;
    if (nicknameSetupRequired) return page === "nicknameSetup" ? null : "nicknameSetup";
    if (preferenceSetupRequired) return page === "preferenceSetup" ? null : "preferenceSetup";
    if (["login", "signup", "callback", "nicknameSetup", "preferenceSetup"].includes(page)) return "profile";
    return null;
}
