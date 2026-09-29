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
    const kakaoLoadedRef = useRef(false);

    const [address, setAddress] = useState(
        user.address ?? ""
    );

    const [location, setLocation] = useState(null);

    const [theaters, setTheaters] = useState([]);

    const [selectedTheaters, setSelectedTheaters] = useState(
        (user.preferredTheaters ?? []).map(
            (item) => item.theaterId
        )
    );

    const [selectedSeats, setSelectedSeats] = useState(
        user.preferredSeats ?? []
    );

    const [loading, setLoading] = useState(false);
    const [locationLoading, setLocationLoading] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        loadKakaoMap();

        return () => {
            theaterMarkersRef.current.forEach((marker) => {
                marker.setMap(null);
            });

            theaterMarkersRef.current = [];

            if (markerRef.current) {
                markerRef.current.setMap(null);
            }

            mapInstance.current = null;
        };
    }, []);

    function loadKakaoMap() {
        const key =
            import.meta.env.VITE_KAKAO_MAP_JS_KEY;

        if (!key) {
            setError(
                "VITE_KAKAO_MAP_JS_KEY가 설정되지 않았습니다."
            );
            return;
        }

        if (window.kakao?.maps) {
            kakaoLoadedRef.current = true;
            initMap();
            return;
        }

        const existingScript = document.querySelector(
            'script[data-kakao-map="true"]'
        );

        if (existingScript) {
            existingScript.addEventListener(
                "load",
                handleKakaoLoad
            );

            return;
        }

        const script = document.createElement("script");

        script.setAttribute(
            "data-kakao-map",
            "true"
        );

        script.src =
            `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${key}&autoload=false&libraries=services`;

        script.async = true;

        script.onload = handleKakaoLoad;

        script.onerror = () => {
            setError(
                "카카오맵 SDK를 불러오지 못했습니다. JavaScript Key와 도메인 설정을 확인해주세요."
            );
        };

        document.head.appendChild(script);
    }

    function handleKakaoLoad() {
        if (!window.kakao?.maps) {
            setError(
                "카카오맵 SDK가 정상적으로 로드되지 않았습니다."
            );
            return;
        }

        window.kakao.maps.load(() => {
            kakaoLoadedRef.current = true;

            initMap();
        });
    }

    function initMap() {
        if (
            !mapRef.current ||
            !window.kakao?.maps
        ) {
            return;
        }

        if (mapInstance.current) {
            return;
        }

        const kakao = window.kakao;

        const defaultPosition =
            new kakao.maps.LatLng(
                37.5665,
                126.978
            );

        mapInstance.current =
            new kakao.maps.Map(
                mapRef.current,
                {
                    center: defaultPosition,
                    level: 6,
                }
            );
    }

    function getCurrentLocation() {
        if (!navigator.geolocation) {
            setError(
                "이 브라우저에서는 위치 정보를 사용할 수 없습니다."
            );
            return;
        }

        if (!kakaoLoadedRef.current) {
            setError(
                "카카오맵이 아직 로딩되지 않았습니다."
            );
            return;
        }

        setLocationLoading(true);
        setError("");

        navigator.geolocation.getCurrentPosition(
            async (position) => {
                const latitude =
                    position.coords.latitude;

                const longitude =
                    position.coords.longitude;

                try {
                    await setResidenceFromLocation(
                        latitude,
                        longitude
                    );
                } catch (e) {
                    setError(
                        e.message ??
                        "현재 위치를 처리하지 못했습니다."
                    );
                } finally {
                    setLocationLoading(false);
                }
            },
            (error) => {
                setLocationLoading(false);

                if (
                    error.code ===
                    error.PERMISSION_DENIED
                ) {
                    setError(
                        "위치 권한이 거부되었습니다. 브라우저에서 위치 권한을 허용해주세요."
                    );
                    return;
                }

                setError(
                    "현재 위치를 가져오지 못했습니다."
                );
            },
            {
                enableHighAccuracy: true,
                timeout: 10000,
                maximumAge: 0,
            }
        );
    }

    async function setResidenceFromLocation(
        latitude,
        longitude
    ) {
        const kakao = window.kakao;

        const position =
            new kakao.maps.LatLng(
                latitude,
                longitude
            );

        mapInstance.current.setCenter(
            position
        );

        mapInstance.current.setLevel(5);

        if (markerRef.current) {
            markerRef.current.setMap(null);
        }

        markerRef.current =
            new kakao.maps.Marker({
                map: mapInstance.current,
                position,
            });

        setLocation({
            latitude,
            longitude,
        });

        const geocoder =
            new kakao.maps.services.Geocoder();

        const addressInfo =
            await new Promise(
                (
                    resolve,
                    reject
                ) => {
                    geocoder.coord2Address(
                        longitude,
                        latitude,
                        (
                            result,
                            status
                        ) => {
                            if (
                                status !==
                                kakao.maps.services.Status.OK
                            ) {
                                reject(
                                    new Error(
                                        "현재 위치의 주소를 가져오지 못했습니다."
                                    )
                                );
                                return;
                            }

                            resolve(result);
                        }
                    );
                }
            );

        const first =
            addressInfo?.[0];

        const roadAddress =
            first?.road_address?.address_name;

        const jibunAddress =
            first?.address?.address_name;

        const resolvedAddress =
            roadAddress ??
            jibunAddress;

        if (!resolvedAddress) {
            throw new Error(
                "현재 위치의 주소를 확인하지 못했습니다."
            );
        }

        setAddress(
            resolvedAddress
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

            const text =
                await response.text();

            if (!response.ok) {
                throw new Error(
                    `주변 영화관 조회 실패 (${response.status})`
                );
            }

            const data =
                JSON.parse(text);

            setTheaters(data);

            renderTheaterMarkers(
                data
            );
        } catch (e) {
            setError(
                e.message ??
                "주변 영화관을 가져오지 못했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    function renderTheaterMarkers(
        data
    ) {
        if (!mapInstance.current) {
            return;
        }

        theaterMarkersRef.current.forEach(
            (marker) => {
                marker.setMap(null);
            }
        );

        theaterMarkersRef.current =
            data.map((theater) => {
                const position =
                    new window.kakao.maps.LatLng(
                        theater.latitude,
                        theater.longitude
                    );

                return new window.kakao.maps.Marker({
                    map: mapInstance.current,
                    position,
                    title: theater.name,
                });
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

                setError("");

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

                setError("");

                return [
                    ...current,
                    position,
                ];
            }
        );
    }

    async function save() {
        if (!address) {
            setError(
                "먼저 현재 위치에서 거주지를 조회해주세요."
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
                            birthDate:
                            user.birthDate,
                            address,
                            preferredTheaterIds:
                            selectedTheaters,
                            preferredSeatPositions:
                            selectedSeats,
                        }),
                    }
                );

            const text =
                await response.text();

            let data = null;

            try {
                data =
                    JSON.parse(text);
            } catch {
                data = null;
            }

            if (!response.ok) {
                throw new Error(
                    data?.message ??
                    `회원정보 저장 실패 (${response.status})`
                );
            }

            onSaved(data);
        } catch (e) {
            setError(
                e.message ??
                "회원정보 저장에 실패했습니다."
            );
        }
    }

    return (
        <div className="preference-container">

            <section>
                <h2>
                    거주지
                </h2>

                <p className="help">
                    현재 위치를 Kakao Map으로 조회하여
                    주소를 자동으로 가져옵니다.
                </p>

                <button
                    type="button"
                    className="primary-button"
                    onClick={getCurrentLocation}
                    disabled={locationLoading}
                >
                    {locationLoading
                        ? "현재 위치 확인 중..."
                        : "현재 위치로 거주지 조회"}
                </button>

                <div
                    ref={mapRef}
                    className="kakao-map"
                />

                {address && (
                    <div className="selected-address">
                        현재 위치에서 조회된 주소:
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
                        {selectedTheaters.length}/5
                    </span>
                </div>

                <p className="help">
                    현재 위치 기준 가까운 영화관입니다.
                    3~5곳을 선택해주세요.
                </p>

                {loading && (
                    <p>
                        주변 영화관을 검색하는 중...
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
                                        {theater.name}
                                    </strong>

                                    <span>
                                        {theater.brand}
                                    </span>
                                </div>

                                <div>
                                    <small>
                                        {theater.address}
                                    </small>

                                    <b>
                                        {theater.distance}m
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
                        {selectedSeats.length}/6
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