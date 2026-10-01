import client from "./client";

export const getMainMovieChart = async () => {
    const response = await client.get("/api/main");
    return response.data;
};