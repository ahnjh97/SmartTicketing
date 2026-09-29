import { useEffect, useRef, useState } from "react";

const API = "http://localhost:8080";

const SEATS = [
    ["SIDE_FRONT", "좌측 · 앞"],
    ["SIDE_MIDDLE", "좌측 · 가운데"],
    ["SIDE_REAR", "좌측 · 뒤"],
    ["MIDDLE_FRONT", "중간 · 앞"],
    ["MIDDLE_MIDDLE", "중간 · 가운데"],
    ["MIDDLE_REAR", "중간 · 뒤"],
];

export default function ResidencePreference({
                                                user,
                                                onSaved,
                                            }) {
    const mapRef = useRef(null);
    const mapInstance = useRef(null);
    const markerRef = useRef(null);
    const theaterMarkersRef = useRef([]);

    const [addressQuery, setAddressQuery] =
        useState("");

    const [address, setAddress] =
        useState(user.address ?? "");

    const [selectedLocation, setSelectedLocation] =
        useState(null);

    const [searchResults, setSearchResults] =
        useState([]);

    const [theaters, setTheaters] =
        useState([]);

    const [selectedTheaters, setSelectedTheaters] =
        useState(
            (user.preferredTheaters ?? [])
                .map((item) => item.theaterId)
        );

    const [selectedSeats, setSelectedSeats] =
        useState(
            user.preferredSeats ?? []
        );

    const [loading, setLoading] =
        useState(false);

    const [error, setError] =
        useState("");

    useEffect(() => {
        loadKakaoMap();
    }, []);

    function loadKakaoMap() {
        const key =
            import.meta.env
                .VITE_KAKAO_MAP_JS_KEY;

        if (!key) {
            setError(
                "VITE_KAKAO_MAP_JS_KEY가 설정되지 않았습니다."
            );
            return;
        }

        if (window.kakao?.maps) {
            initMap();
            return;
        }

        const script =
            document.createElement("script");

        script.src =
            `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${key}&libraries=services`;

        script.onload = () => {
            window.kakao.maps.load(
                initMap
            );
        };

        script.onerror = () => {
            setError(
                "카카오맵을 불러오지 못했습니다."
            );
        };

        document.head.appendChild(
            script
        );
    }

    function initMap() {
        const kakao =
            window.kakao;

        const defaultPosition =
            new kakao.maps.LatLng(
                37.5665,
                126.978
            );

        mapInstance.current =
            new kakao.maps.Map(
                mapRef.current,
                {
                    center:
                    defaultPosition,
                    level: 6,
                }
            );
    }

    function searchAddress() {
        if (!addressQuery.trim()) {
            return;
        }

        const geocoder =
            new window.kakao.maps.services.Geocoder();

        geocoder.addressSearch(
            addressQuery.trim(),
            (
                results,
                status
            ) => {
                if (
                    status !==
                    window.kakao.maps.services.Status.OK
                ) {
                    setSearchResults([]);
                    setError(
                        "주소를 찾지 못했습니다."
                    );
                    return;
                }

                setError("");
                setSearchResults(
                    results.slice(0, 5)
                );
            }
        );
    }

    async function selectAddress(
        result
    ) {
        const latitude =
            Number(result.y);

        const longitude =
            Number(result.x);

        setAddress(
            result.road_address?.address_name ??
            result.address_name
        );

        setAddressQuery(
            result.road_address?.address_name ??
            result.address_name
        );

        setSearchResults([]);

        setSelectedLocation({
            latitude,
            longitude,
        });

        const position =
            new window.kakao.maps.LatLng(
                latitude,
                longitude
            );

        mapInstance.current.setCenter(
            position
        );

        mapInstance.current.setLevel(
            5
        );

        if (markerRef.current) {
            markerRef.current.setMap(
                null
            );
        }

        markerRef.current =
            new window.kakao.maps.Marker(
                {
                    map: mapInstance.current,
                    position,
                }
            );

        await loadNearbyTheaters(
            latitude,
            longitude
        );
    }

    async function loadNearbyTheaters(
        latitude,
        longitude
    ) {
        setLoading(true);
        setError("");

        try {
            const token =
                localStorage.getItem(
                    "accessToken"
                );

            const response =
                await fetch(
                    `${API}/api/theaters/nearby?latitude=${latitude}&longitude=${longitude}&radius=10000`,
                    {
                        headers: {
                            Authorization:
                                `Bearer ${token}`,
                        },
                    }
                );

            if (!response.ok) {
                throw new Error(
                    "주변 영화관을 가져오지 못했습니다."
                );
            }

            const data =
                await response.json();

            setTheaters(data);

            renderTheaterMarkers(
                data
            );
        } catch (e) {
            setError(
                e.message
            );
        } finally {
            setLoading(false);
        }
    }

    function renderTheaterMarkers(
        data
    ) {
        theaterMarkersRef.current.forEach(
            (marker) =>
                marker.setMap(null)
        );

        theaterMarkersRef.current =
            data.map((theater) => {

                const position =
                    new window.kakao.maps.LatLng(
                        theater.latitude,
                        theater.longitude
                    );

                return new window.kakao.maps.Marker(
                    {
                        map:
                        mapInstance.current,
                        position,
                        title:
                        theater.name,
                    }
                );
            });
    }

    function toggleTheater(
        theaterId
    ) {
        setSelectedTheaters(
            (current) => {

                if (
                    current.includes(
                        theaterId
                    )
                ) {
                    return current.filter(
                        (id) =>
                            id !== theaterId
                    );
                }

                if (
                    current.length >= 5
                ) {
                    setError(
                        "선호 영화관은 최대 5곳까지 선택할 수 있습니다."
                    );

                    return current;
                }

                if (
                    current.length < 5
                ) {
                    setError("");
                }

                return [
                    ...current,
                    theaterId,
                ];
            }
        );
    }

    function toggleSeat(
        position
    ) {
        setSelectedSeats(
            (current) => {

                if (
                    current.includes(
                        position
                    )
                ) {
                    return current.filter(
                        (item) =>
                            item !== position
                    );
                }

                if (
                    current.length >= 6
                ) {
                    setError(
                        "선호 좌석은 최대 6개까지 선택할 수 있습니다."
                    );

                    return current;
                }

                return [
                    ...current,
                    position,
                ];
            }
        );
    }

    async function save() {
        if (!address.trim()) {
            setError(
                "거주지를 선택해주세요."
            );
            return;
        }

        if (
            selectedTheaters.length < 3 ||
            selectedTheaters.length > 5
        ) {
            setError(
                "선호 영화관을 3~5곳 선택해주세요."
            );
            return;
        }

        if (
            selectedSeats.length < 1
        ) {
            setError(
                "선호 좌석을 1개 이상 선택해주세요."
            );
            return;
        }

        try {
            const token =
                localStorage.getItem(
                    "accessToken"
                );

            const response =
                await fetch(
                    `${API}/api/users/me`,
                    {
                        method: "PATCH",
                        headers: {
                            "Content-Type":
                                "application/json",
                            Authorization:
                                `Bearer ${token}`,
                        },
                        body: JSON.stringify({
                            address,
                            preferredTheaterIds:
                            selectedTheaters,
                            preferredSeatPositions:
                            selectedSeats,
                        }),
                    }
                );

            const data =
                await response.json();

            if (!response.ok) {
                throw new Error(
                    data.message ??
                    "회원정보 저장에 실패했습니다."
                );
            }

            onSaved(data);

        } catch (e) {
            setError(
                e.message
            );
        }
    }

    return (
        <div className="preference-container">

            <section>
                <h2>
                    거주지 설정
                </h2>

                <div className="address-search">
                    <input
                        value={
                            addressQuery
                        }
                        onChange={(e) =>
                            setAddressQuery(
                                e.target.value
                            )
                        }
                        onKeyDown={(e) => {
                            if (
                                e.key ===
                                "Enter"
                            ) {
                                searchAddress();
                            }
                        }}
                        placeholder="도로명 주소를 입력해주세요."
                    />

                    <button
                        type="button"
                        onClick={
                            searchAddress
                        }
                    >
                        검색
                    </button>
                </div>

                {searchResults.length >
                    0 && (
                        <div className="address-results">
                            {searchResults.map(
                                (result, index) => (
                                    <button
                                        type="button"
                                        key={
                                            `${result.address_name}-${index}`
                                        }
                                        onClick={() =>
                                            selectAddress(
                                                result
                                            )
                                        }
                                    >
                                        <strong>
                                            {
                                                result.road_address?.address_name ??
                                                result.address_name
                                            }
                                        </strong>

                                        {result.road_address
                                            ?.address_name && (
                                            <small>
                                                지번:{" "}
                                                {
                                                    result.address_name
                                                }
                                            </small>
                                        )}
                                    </button>
                                )
                            )}
                        </div>
                    )}

                <div
                    ref={mapRef}
                    className="kakao-map"
                />

                {address && (
                    <div className="selected-address">
                        선택된 거주지:
                        <strong>
                            {address}
                        </strong>
                    </div>
                )}
            </section>

            <section>
                <div className="section-title">
                    <h2>
                        주변 영화관
                    </h2>

                    <span>
                        {selectedTheaters.length}
                        /5
                    </span>
                </div>

                <p className="help">
                    거주지 기준 가까운 영화관입니다.
                    3~5곳을 선택해주세요.
                </p>

                {loading && (
                    <p>
                        영화관을 검색하는 중...
                    </p>
                )}

                <div className="theater-list">

                    {theaters.map(
                        (theater) => (
                            <button
                                type="button"
                                key={
                                    theater.theaterId
                                }
                                className={
                                    selectedTheaters.includes(
                                        theater.theaterId
                                    )
                                        ? "theater-item selected"
                                        : "theater-item"
                                }
                                onClick={() =>
                                    toggleTheater(
                                        theater.theaterId
                                    )
                                }
                            >
                                <div>
                                    <strong>
                                        {
                                            theater.name
                                        }
                                    </strong>

                                    <span>
                                        {
                                            theater.brand
                                        }
                                    </span>
                                </div>

                                <div>
                                    <small>
                                        {
                                            theater.address
                                        }
                                    </small>

                                    <b>
                                        {
                                            theater.distance
                                        }
                                        m
                                    </b>
                                </div>
                            </button>
                        )
                    )}

                </div>
            </section>

            <section>
                <div className="section-title">
                    <h2>
                        선호 좌석
                    </h2>

                    <span>
                        {selectedSeats.length}
                        /6
                    </span>
                </div>

                <div className="seat-grid">

                    {SEATS.map(
                        ([value, label]) => (
                            <button
                                type="button"
                                key={value}
                                className={
                                    selectedSeats.includes(
                                        value
                                    )
                                        ? "seat selected"
                                        : "seat"
                                }
                                onClick={() =>
                                    toggleSeat(
                                        value
                                    )
                                }
                            >
                                {label}
                            </button>
                        )
                    )}

                </div>
            </section>

            {error && (
                <p className="error-message">
                    {error}
                </p>
            )}

            <button
                type="button"
                className="primary-button"
                onClick={save}
            >
                선호 정보 저장
            </button>
        </div>
    );
}