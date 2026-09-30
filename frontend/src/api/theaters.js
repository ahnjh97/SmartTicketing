import { request } from "./client.js";

export const theaterApi = {
    nearby: ({ latitude, longitude, radius = 10000 }) => request("/api/theaters/nearby", {
        query: { latitude, longitude, radius },
    }),
};
