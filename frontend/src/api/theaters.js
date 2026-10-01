import { request } from "./client.js";

export const theaterApi = {
    nearby: ({
        address,
        latitude,
        longitude,
        radius = 10000,
        sort = "DISTANCE",
    }) => request("/api/theaters/nearby", {
        query: {
            address,
            latitude,
            longitude,
            radius,
            sort,
        },
    }),
};
