import { useEffect, useMemo, useRef, useState } from "react";

const API = "http://localhost:8080";

const ROWS = "ABCDEFGHIJ".split("");
const SEATS_PER_ROW = 12;

const SEAT_POSITION_LABELS = {
    SIDE_FRONT: "좌측 · 앞",
    SIDE_MIDDLE: "좌측 · 가운데",
    SIDE_REAR: "좌측 · 뒤",
    MIDDLE_FRONT: "중간 · 앞",
    MIDDLE_MIDDLE: "중간 · 가운데",
    MIDDLE_REAR: "중간 · 뒤",
};

function getSeatPosition(rowIndex, seatNumber) {
    let vertical;

    if (rowIndex <= 2) {
        vertical = "FRONT";
    } else if (rowIndex <= 6) {
        vertical = "MIDDLE";
    } else {
        vertical = "REAR";
    }

    const horizontal =
        seatNumber <= 2 || seatNumber >= 11
            ? "SIDE"
            : "MIDDLE";

    return `${horizontal}_${vertical}`;
}

function getSeatLabel(position) {
    return SEAT_POSITION_LABELS[position] ?? position;
}

export default function ResidencePreference({
                                                user,
                                                onSaved,
                                            }) {
    const mapRef = useRef(null);
    const mapInstanceRef = useRef(null);

    const currentMarkerRef = useRef(null);
    const currentCircleRef = useRef(null);

    const theaterMarkersRef = useRef([]);
    const theaterOverlaysRef = useRef([]);

    const kakaoReadyRef = useRef(false);

    const [address, setAddress] = useState(
        user.address ?? ""
    );

    const [location, setLocation] = useState(null);

    const [theaters, setTheaters] = useState([]);

    const [selectedTheaters, setSelectedTheaters] =
        useState(
            (user.preferredTheaters ?? []).map(
                (item) => item.theaterId
            )
        );

    /*
     * 실제 좌석 번호 → SeatPosition
     *
     * 예:
     * {
     *   SIDE_FRONT: "A1",
     *   MIDDLE_FRONT: "A6"
     * }
     */
    const [selectedSeatMap, setSelectedSeatMap] =
        useState({});

    const [locationLoading, setLocationLoading] =
        useState(false);

    const [theaterLoading, setTheaterLoading] =
        useState(false);

    const [saving, setSaving] = useState(false);

    const [error, setError] = useState("");

    const [message, setMessage] = useState("");

    const selectedSeats = useMemo(
        () => Object.keys(selectedSeatMap),
        [selectedSeatMap]
    );

    useEffect(() => {
        loadKakaoMap();

        return () => {
            theaterMarkersRef.current.forEach(
                (marker) => marker.setMap(null)
            );

            theaterOverlaysRef.current.forEach(
                (overlay) => overlay.setMap(null)
            );

            if (currentMarkerRef.current) {
                currentMarkerRef.current.setMap(null);
            }

            if (currentCircleRef.current) {
                currentCircleRef.current.setMap(null);
            }
        };
    }, []);

    useEffect(() => {
        /*
         * 기존에 저장된 SeatPosition이 있다면
         * 화면에서 임의의 좌석을 하나씩 표시한다.
         */
        if (
            !user.preferredSeats ||
            user.preferredSeats.length === 0
        ) {
            return;
        }

        const initial = {};

        user.preferredSeats.forEach(
            (position, index) => {
                const rowIndex =
                    position.includes("FRONT")
                        ? index % 3
                        : position.includes("MIDDLE")
                            ? 3 + (index % 4)
                            : 7 + (index % 3);

                let seatNumber;

                if (position.startsWith("SIDE")) {
                    seatNumber =
                        index % 2 === 0
                            ? 1
                            : 12;
                } else {
                    seatNumber = 5 + (index % 4);
                }

                const row =
                    ROWS[
                        Math.min(
                            rowIndex,
                            ROWS.length - 1
                        )
                        ];

                initial[position] =
                    `${row}${seatNumber}`;
            }
        );

        setSelectedSeatMap(initial);
    }, [user.preferredSeats]);

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
            kakaoReadyRef.current = true;
            initializeMap();
            return;
        }

        const existingScript =
            document.querySelector(
                'script[data-smart-ticketing-kakao-map="true"]'
            );

        if (existingScript) {
            existingScript.addEventListener(
                "load",
                handleKakaoScriptLoad
            );
            return;
        }

        const script =
            document.createElement("script");

        script.setAttribute(
            "data-smart-ticketing-kakao-map",
            "true"
        );

        script.src =
            `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${key}&autoload=false&libraries=services`;

        script.async = true;

        script.onload =
            handleKakaoScriptLoad;

        script.onerror = () => {
            setError(
                "카카오맵 SDK를 불러오지 못했습니다."
            );
        };

        document.head.appendChild(script);
    }

    function handleKakaoScriptLoad() {
        if (!window.kakao?.maps) {
            setError(
                "카카오맵 SDK가 정상적으로 로드되지 않았습니다."
            );
            return;
        }

        window.kakao.maps.load(() => {
            kakaoReadyRef.current = true;
            initializeMap();
        });
    }

    function initializeMap() {
        if (
            !mapRef.current ||
            !window.kakao?.maps
        ) {
            return;
        }

        if (mapInstanceRef.current) {
            return;
        }

        const kakao =
            window.kakao;

        const defaultPosition =
            new kakao.maps.LatLng(
                37.5665,
                126.978
            );

        mapInstanceRef.current =
            new kakao.maps.Map(
                mapRef.current,
                {
                    center: defaultPosition,
                    level: 7,
                }
            );
    }

    function getCurrentLocation() {
        if (!navigator.geolocation) {
            setError(
                "현재 브라우저에서 위치 정보를 사용할 수 없습니다."
            );
            return;
        }

        if (!kakaoReadyRef.current) {
            setError(
                "카카오맵이 아직 준비되지 않았습니다."
            );
            return;
        }

        setError("");
        setMessage("");
        setLocationLoading(true);

        navigator.geolocation.getCurrentPosition(
            async (position) => {
                const latitude =
                    position.coords.latitude;

                const longitude =
                    position.coords.longitude;

                try {
                    await applyCurrentLocation(
                        latitude,
                        longitude
                    );
                } catch (e) {
                    setError(
                        e.message ??
                        "현재 위치 처리에 실패했습니다."
                    );
                } finally {
                    setLocationLoading(false);
                }
            },
            (geoError) => {
                setLocationLoading(false);

                if (
                    geoError.code ===
                    geoError.PERMISSION_DENIED
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

    async function applyCurrentLocation(
        latitude,
        longitude
    ) {
        const kakao =
            window.kakao;

        const position =
            new kakao.maps.LatLng(
                latitude,
                longitude
            );

        setLocation({
            latitude,
            longitude,
        });

        mapInstanceRef.current.setCenter(
            position
        );

        mapInstanceRef.current.setLevel(
            6
        );

        /*
         * 현재 위치 마커
         */
        if (currentMarkerRef.current) {
            currentMarkerRef.current.setMap(
                null
            );
        }

        currentMarkerRef.current =
            new kakao.maps.Marker({
                map: mapInstanceRef.current,
                position,
                title: "현재 위치",
            });

        /*
         * 3km 반경
         */
        if (currentCircleRef.current) {
            currentCircleRef.current.setMap(
                null
            );
        }

        currentCircleRef.current =
            new kakao.maps.Circle({
                map: mapInstanceRef.current,
                center: position,
                radius: 3000,
                strokeWeight: 2,
                strokeColor: "#222222",
                strokeOpacity: 0.7,
                strokeStyle: "solid",
                fillColor: "#555555",
                fillOpacity: 0.08,
            });

        /*
         * 현재 위치 → 주소
         *
         * Kakao Local REST API가 아니라
         * JavaScript SDK Geocoder 사용.
         */
        const geocoder =
            new kakao.maps.services.Geocoder();

        const addressResult =
            await new Promise(
                (resolve, reject) => {
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
            addressResult?.[0];

        const roadAddress =
            first?.road_address?.address_name;

        const jibunAddress =
            first?.address?.address_name;

        const resolvedAddress =
            roadAddress ??
            jibunAddress;

        if (!resolvedAddress) {
            throw new Error(
                "현재 위치에서 주소를 확인하지 못했습니다."
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
        setTheaterLoading(true);
        setError("");

        try {
            const token =
                localStorage.getItem(
                    "accessToken"
                );

            /*
             * 이 엔드포인트 뒤에서
             * 팀원 API를 호출하도록 맞추면 된다.
             *
             * 프론트는 팀원 API 자체를 직접 호출하지 않는다.
             */
            const response =
                await fetch(
                    `${API}/api/theaters/nearby?latitude=${latitude}&longitude=${longitude}&radius=3000`,
                    {
                        headers: {
                            Authorization:
                                `Bearer ${token}`,
                        },
                    }
                );

            const text =
                await response.text();

            let data = [];

            try {
                data =
                    JSON.parse(text);
            } catch {
                data = [];
            }

            if (!response.ok) {
                throw new Error(
                    data?.message ??
                    `주변 영화관 조회 실패 (${response.status})`
                );
            }

            /*
             * 팀원 API에서
             *
             * transitMinutes
             * 또는 durationMinutes
             *
             * 형태로 대중교통 시간을 내려주는 것을 기준으로 한다.
             */
            const normalized =
                data.map(
                    (theater) => ({
                        ...theater,
                        transitMinutes:
                            Number.isFinite(
                                Number(
                                    theater.transitMinutes
                                )
                            )
                                ? Number(
                                    theater.transitMinutes
                                )
                                : Number.isFinite(
                                    Number(
                                        theater.durationMinutes
                                    )
                                )
                                    ? Number(
                                        theater.durationMinutes
                                    )
                                    : null,
                    })
                );

            normalized.sort(
                (
                    a,
                    b
                ) => {
                    if (
                        a.transitMinutes != null &&
                        b.transitMinutes != null
                    ) {
                        return (
                            a.transitMinutes -
                            b.transitMinutes
                        );
                    }

                    if (
                        a.transitMinutes != null
                    ) {
                        return -1;
                    }

                    if (
                        b.transitMinutes != null
                    ) {
                        return 1;
                    }

                    return (
                        Number(
                            a.distance ?? 0
                        ) -
                        Number(
                            b.distance ?? 0
                        )
                    );
                }
            );

            setTheaters(
                normalized
            );

            renderTheaterMarkers(
                normalized
            );
        } catch (e) {
            setError(
                e.message ??
                "주변 영화관 조회에 실패했습니다."
            );
        } finally {
            setTheaterLoading(false);
        }
    }

    function renderTheaterMarkers(
        theaterList
    ) {
        if (
            !mapInstanceRef.current ||
            !window.kakao?.maps
        ) {
            return;
        }

        theaterMarkersRef.current.forEach(
            (marker) =>
                marker.setMap(null)
        );

        theaterOverlaysRef.current.forEach(
            (overlay) =>
                overlay.setMap(null)
        );

        theaterMarkersRef.current = [];
        theaterOverlaysRef.current = [];

        theaterList.forEach(
            (
                theater,
                index
            ) => {
                if (
                    theater.latitude == null ||
                    theater.longitude == null
                ) {
                    return;
                }

                const position =
                    new window.kakao.maps.LatLng(
                        theater.latitude,
                        theater.longitude
                    );

                const marker =
                    new window.kakao.maps.Marker({
                        map: mapInstanceRef.current,
                        position,
                        title:
                        theater.name,
                    });

                const minutes =
                    theater.transitMinutes;

                const overlay =
                    new window.kakao.maps.CustomOverlay({
                        map: mapInstanceRef.current,
                        position,
                        content: `
                            <div style="
                                background:white;
                                border:1px solid #222;
                                border-radius:10px;
                                padding:6px 9px;
                                font-size:12px;
                                font-weight:700;
                                box-shadow:0 2px 6px rgba(0,0,0,0.15);
                                white-space:nowrap;
                            ">
                                ${index + 1}위
                                ${
                            minutes != null
                                ? ` · ${minutes}분`
                                : ""
                        }
                            </div>
                        `,
                        yAnchor: 1.8,
                    });

                theaterMarkersRef.current.push(
                    marker
                );

                theaterOverlaysRef.current.push(
                    overlay
                );
            }
        );
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
        row,
        seatNumber
    ) {
        const seatId =
            `${row}${seatNumber}`;

        const rowIndex =
            ROWS.indexOf(row);

        const position =
            getSeatPosition(
                rowIndex,
                seatNumber
            );

        setSelectedSeatMap(
            (current) => {
                /*
                 * 같은 실제 좌석을 다시 클릭하면 해제
                 */
                if (
                    current[position] ===
                    seatId
                ) {
                    const next = {
                        ...current,
                    };

                    delete next[position];

                    return next;
                }

                /*
                 * 이미 같은 위치가 선택되어 있으면
                 * 새로운 좌석으로 교체
                 */
                return {
                    ...current,
                    [position]: seatId,
                };
            }
        );

        setError("");
    }

    function isSeatSelected(
        row,
        seatNumber
    ) {
        const seatId =
            `${row}${seatNumber}`;

        return Object.values(
            selectedSeatMap
        ).includes(seatId);
    }

    function getSelectedTheatersInPriorityOrder() {
        return theaters
            .filter((theater) =>
                selectedTheaters.includes(
                    theater.theaterId
                )
            )
            .map(
                (theater) =>
                    theater.theaterId
            );
    }

    async function save() {
        setError("");
        setMessage("");

        if (!location) {
            setError(
                "먼저 현재 위치를 조회해주세요."
            );
            return;
        }

        if (!address) {
            setError(
                "현재 위치의 거주지를 확인해주세요."
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
            selectedSeats.length < 1 ||
            selectedSeats.length > 6
        ) {
            setError(
                "선호 좌석 위치를 1~6개 선택해주세요."
            );
            return;
        }

        setSaving(true);

        try {
            const token =
                localStorage.getItem(
                    "accessToken"
                );

            /*
             * 영화관 선택 순서를
             * 대중교통 우선순위 순서로 정렬해서 전달
             */
            const theaterIds =
                getSelectedTheatersInPriorityOrder();

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
                            nickname:
                            user.nickname,
                            birthDate:
                            user.birthDate,
                            address,
                            preferredTheaterIds:
                            theaterIds,
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

            setMessage(
                "거주지, 선호 영화관, 선호 좌석이 저장되었습니다."
            );

            onSaved(data);
        } catch (e) {
            setError(
                e.message ??
                "회원정보 저장에 실패했습니다."
            );
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="preference-container">

            {/* ========================= */}
            {/* 거주지 / 현재 위치 */}
            {/* ========================= */}

            <section>
                <h2>
                    거주지 설정
                </h2>

                <p className="help">
                    현재 위치를 기준으로 거주지를 자동
                    확인하고 주변 3km 영화관을 조회합니다.
                </p>

                <button
                    type="button"
                    className="primary-button"
                    onClick={
                        getCurrentLocation
                    }
                    disabled={
                        locationLoading
                    }
                >
                    {locationLoading
                        ? "현재 위치 확인 중..."
                        : "현재 위치로 조회"}
                </button>

                <div
                    ref={mapRef}
                    className="kakao-map"
                />

                {location && (
                    <div className="location-info">
                        현재 위치:
                        <strong>
                            {location.latitude.toFixed(
                                6
                            )}
                            ,{" "}
                            {location.longitude.toFixed(
                                6
                            )}
                        </strong>
                    </div>
                )}

                {address && (
                    <div className="selected-address">
                        자동으로 확인된 거주지
                        <strong>
                            {address}
                        </strong>
                    </div>
                )}
            </section>

            {/* ========================= */}
            {/* 주변 영화관 */}
            {/* ========================= */}

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
                    현재 위치 기준 3km 이내의 영화관을
                    대중교통 소요시간순으로 표시합니다.
                </p>

                {theaterLoading && (
                    <p>
                        영화관을 조회하는 중...
                    </p>
                )}

                {!theaterLoading &&
                    theaters.length ===
                    0 &&
                    location && (
                        <p className="help">
                            주변 영화관이 없습니다.
                        </p>
                    )}

                <div className="theater-list">

                    {theaters.map(
                        (
                            theater,
                            index
                        ) => {
                            const selected =
                                selectedTheaters.includes(
                                    theater.theaterId
                                );

                            return (
                                <button
                                    type="button"
                                    key={
                                        theater.theaterId
                                    }
                                    className={
                                        selected
                                            ? "theater-item selected"
                                            : "theater-item"
                                    }
                                    onClick={() =>
                                        toggleTheater(
                                            theater.theaterId
                                        )
                                    }
                                >
                                    <div className="theater-rank">
                                        {index +
                                            1}
                                    </div>

                                    <div className="theater-main">

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

                                        <small>
                                            {
                                                theater.address
                                            }
                                        </small>
                                    </div>

                                    <div className="theater-time">

                                        {theater.transitMinutes !=
                                        null ? (
                                            <>
                                                <strong>
                                                    {
                                                        theater.transitMinutes
                                                    }
                                                    분
                                                </strong>

                                                <small>
                                                    대중교통
                                                </small>
                                            </>
                                        ) : (
                                            <>
                                                <strong>
                                                    -
                                                </strong>

                                                <small>
                                                    시간 정보
                                                    없음
                                                </small>
                                            </>
                                        )}

                                    </div>
                                </button>
                            );
                        }
                    )}

                </div>
            </section>

            {/* ========================= */}
            {/* 선호 좌석 */}
            {/* ========================= */}

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

                <p className="help">
                    실제 영화관 좌석 형태에서
                    선호하는 좌석을 선택하세요.
                    같은 위치 범주의 좌석은 하나만 선택됩니다.
                </p>

                <div className="screen">
                    SCREEN
                </div>

                <div className="seat-grid-real">

                    {ROWS.map(
                        (
                            row,
                            rowIndex
                        ) => (
                            <div
                                className="seat-row"
                                key={row}
                            >
                                {Array.from(
                                    {
                                        length:
                                        SEATS_PER_ROW,
                                    },
                                    (_, index) => {
                                        const seatNumber =
                                            index +
                                            1;

                                        const position =
                                            getSeatPosition(
                                                rowIndex,
                                                seatNumber
                                            );

                                        const selected =
                                            isSeatSelected(
                                                row,
                                                seatNumber
                                            );

                                        return (
                                            <button
                                                type="button"
                                                key={`${row}${seatNumber}`}
                                                className={
                                                    selected
                                                        ? "real-seat selected"
                                                        : "real-seat"
                                                }
                                                onClick={() =>
                                                    toggleSeat(
                                                        row,
                                                        seatNumber
                                                    )
                                                }
                                                title={
                                                    getSeatLabel(
                                                        position
                                                    )
                                                }
                                            >
                                                {row}
                                                {
                                                    seatNumber
                                                }
                                            </button>
                                        );
                                    }
                                )}
                            </div>
                        )
                    )}

                </div>

                <div className="seat-selection-summary">

                    {selectedSeats.length ===
                    0 ? (
                        <span>
                            선택된 선호 좌석이 없습니다.
                        </span>
                    ) : (
                        selectedSeats.map(
                            (position) => (
                                <div
                                    key={
                                        position
                                    }
                                    className="seat-summary-item"
                                >
                                    <strong>
                                        {
                                            selectedSeatMap[
                                                position
                                                ]
                                        }
                                    </strong>

                                    <span>
                                        {
                                            getSeatLabel(
                                                position
                                            )
                                        }
                                    </span>
                                </div>
                            )
                        )
                    )}

                </div>
            </section>

            {error && (
                <p className="error-message">
                    {error}
                </p>
            )}

            {message && (
                <p className="success-message">
                    {message}
                </p>
            )}

            <button
                type="button"
                className="primary-button"
                onClick={save}
                disabled={saving}
            >
                {saving
                    ? "저장 중..."
                    : "선호 정보 저장"}
            </button>

        </div>
    );
}