import React, { useEffect, useState } from "react";
import { getMainMovieChart } from "../api/movies";
import "../styles/HomePage.css"; // 생성한 CSS 임포트

export default function HomePage() {
    const [movies, setMovies] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        getMainMovieChart()
            .then((data) => {
                setMovies(data);
                setLoading(false);
            })
            .catch((error) => {
                console.error("영화 차트 로딩 실패:", error);
                setLoading(false);
            });
    }, []);

    if (loading) {
        return (
            <div className="home-container">
                <div className="loading-box">무비차트를 불러오는 중입니다...</div>
            </div>
        );
    }

    return (
        <div className="home-container">
            <section className="home-section">
                <h2 className="chart-title">무비차트</h2>

                <div className="movie-grid">
                    {movies.map((movie, index) => (
                        <div key={movie.id} className="movie-card">
                            <span className="rank-badge">{index + 1}</span>

                            <img
                                src={movie.posterPath}
                                alt={movie.title}
                                className="poster-img"
                            />

                            <div className="movie-info">
                                <h3 className="movie-title">{movie.title}</h3>

                                <div className="movie-meta">
                                    <span>예매율 <strong>{movie.bookingRate}%</strong></span>
                                    <span>누적관객 <strong>{movie.audienceCount?.toLocaleString()}명</strong></span>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </section>
        </div>
    );
}