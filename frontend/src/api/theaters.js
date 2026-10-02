import { request } from "./client.js";

export const theaterApi = {
    nearby: ({
        address,
        latitude,
        longitude,
        sort = "DISTANCE",
    }) => request("/api/theaters/nearby", {
        query: {
            address,
            latitude,
            longitude,
            sort,
        },
    }),
};
