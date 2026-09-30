import { userApi } from "../api/users.js";
import { getAccessToken, setAccessToken } from "./session.js";

export async function restoreSession(initialUrl) {
    const url = new URL(initialUrl);
    if (url.pathname === "/oauth2/callback") {
        const error = url.searchParams.get("error");
        if (error) throw new Error(error);
        const token = new URLSearchParams(url.hash.slice(1)).get("token");
        if (token) setAccessToken(token);
    }
    const token = getAccessToken();
    if (!token) return { user: null, token: null };
    const user = await userApi.me(token);
    return { user, token };
}
