import { apiUrl, request } from "./client.js";

export const authApi = {
    signup: (body) => request("/api/auth/signup", { method: "POST", body, authenticated: false }),
    login: (body) => request("/api/auth/login", { method: "POST", body, authenticated: false }),
    checkLoginId: (loginId) => request("/api/auth/check-login-id", {
        query: { loginId }, authenticated: false,
    }),
    logout: () => request("/api/auth/logout", { method: "POST" }),
    link: (provider) => request(`/api/auth/link/${provider.toUpperCase()}`, { method: "POST" }),
    socialLoginUrl: (provider) => apiUrl(`/oauth2/authorization/${provider.toLowerCase()}`),
};
