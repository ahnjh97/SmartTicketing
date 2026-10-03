const TOKEN_KEY = "accessToken";
const expirationListeners = new Set();

export const getAccessToken = () =>
    localStorage.getItem(TOKEN_KEY);

export const setAccessToken = (token) =>
    localStorage.setItem(TOKEN_KEY, token);

export const clearAccessToken = () =>
    localStorage.removeItem(TOKEN_KEY);

export function subscribeToSessionExpiration(listener) {
    expirationListeners.add(listener);

    return () =>
        expirationListeners.delete(listener);
}

export function expireSession(requestToken) {
    if (
        !requestToken
        || getAccessToken() !== requestToken
    ) {
        return;
    }

    clearAccessToken();

    expirationListeners.forEach(
        (listener) => listener()
    );
}

export function needsPreferenceSetup(user) {
    return Boolean(
        user
        && user.admin !== true
        && (
            !user.birthDate
            || !user.address?.trim()
            || (user.preferredTheaters ?? []).length < 3
            || (user.preferredSeats ?? []).length < 1
        )
    );
}
