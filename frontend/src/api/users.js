import { request } from "./client.js";

export const userApi = {
    me: (token) => request("/api/users/me", { token }),
    preferenceOptions: () => request("/api/users/preference-options"),
    update: (body) => request("/api/users/me", { method: "PATCH", body }),
    nearbyTheaters: () => request("/api/users/me/nearby-theaters"),
    saveNearbyTheaters: (body) =>
        request("/api/users/me/nearby-theaters", {
            method: "PUT",
            body,
        }),
    withdraw: () => request("/api/users/me", { method: "DELETE" }),
};
