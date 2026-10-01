import {
    useEffect,
    useRef,
    useState,
} from "react";

import { theaterApi, userApi } from "../api";

const ROWS =
    "ABCDEFGHIJ".split("");

const SEATS_PER_ROW = 12;

const SEAT_POSITION_LABELS = {
    SIDE_FRONT: "양옆 · 1번",
    SIDE_MIDDLE: "양옆 · 2번",
    SIDE_REAR: "양옆 · 3번",
    MIDDLE_FRONT: "중앙 · 4번",
    MIDDLE_MIDDLE: "중앙 · 5번",
    MIDDLE_REAR: "중앙 · 6번",
};

function getSeatPosition(
    rowIndex,
    seatNumber
) {
    let vertical;

    if (rowIndex <= 2) {
        vertical = "FRONT";
    } else if (rowIndex <= 6) {
        vertical = "MIDDLE";
    } else {
        vertical = "REAR";
    }

    const horizontal =
        seatNumber <= 2 ||
        seatNumber >= 11
            ? "SIDE"
            : "MIDDLE";

    return `${horizontal}_${vertical}`;
}

function getSeatLabel(
    position
) {
    return (
        SEAT_POSITION_LABELS[position] ??
        position
    );
}

function restorePreferredSeats(
    preferredSeats = []
) {
    const seen = new Set();

    return preferredSeats
        .map((item) => {
            const position =
                typeof item === "string"
                    ? item
                    : item?.position;

            if (!position || seen.has(position)) {
                return null;
            }

            seen.add(position);

            return {
                position,
                priority: seen.size,
            };
        })
        .filter(Boolean)
        .slice(0, 3);
}

function initializeMap(
    mapRef,
    mapInstanceRef
) {
    if (
        !mapRef.current ||
        !window.kakao?.maps ||
        mapInstanceRef.current
    ) {
        return;
    }

    const kakao =
        window.kakao;

    mapInstanceRef.current =
        new kakao.maps.Map(
            mapRef.current,
            {
                center:
                    new kakao.maps.LatLng(
                        37.5665,
                        126.978
                    ),
                level: 7,
            }
        );
}

function toNullableNumber(
    value
) {
    if (
        value === null ||
        value === undefined ||
        value === ""
    ) {
        return null;
    }

    const number =
        Number(value);

    return Number.isFinite(
        number
    )
        ? number
        : null;
}

export default function ResidencePreference({
                                                user,
                                                onSaved,
                                            }) {
    const mapRef =
        useRef(null);

    const mapInstanceRef =
        useRef(null);

    const currentMarkerRef =
        useRef(null);

    const currentCircleRef =
        useRef(null);

    const theaterMarkersRef =
        useRef([]);

    const theaterOverlaysRef =
        useRef([]);

    const kakaoReadyRef =
        useRef(false);

    const [birthDate, setBirthDate] =
        useState(
            user.birthDate ?? ""
        );

    const [address, setAddress] =
        useState(
            user.address ?? ""
        );

    const [location, setLocation] =
        useState(null);

    const [locationSource, setLocationSource] =
        useState(null);

    const [theaters, setTheaters] =
        useState([]);

    const [selectedTheaters, setSelectedTheaters] =
        useState(
            (user.preferredTheaters ?? [])
                .map((item) => Number(item.theaterId))
                .filter((theaterId) => Number.isFinite(theaterId))
        );

    const [selectedSeats, setSelectedSeats] =
        useState(
            () =>
                restorePreferredSeats(
                    user.preferredSeats
                )
        );

    const [hoveredSeatPosition, setHoveredSeatPosition] =
        useState(null);

    const [locationLoading, setLocationLoading] =
        useState(false);

    const [theaterLoading, setTheaterLoading] =
        useState(false);

    const [saving, setSaving] =
        useState(false);

    const [error, setError] =
        useState("");

    const [message, setMessage] =
        useState("");

    useEffect(() => {
        let active = true;
        let script;

        function handleLoad() {
            if (!active) {
                return;
            }

            if (!window.kakao?.maps) {
                setError(
                    "카카오맵 SDK가 정상적으로 로드되지 않았습니다."
                );

                return;
            }

            window.kakao.maps.load(
                () => {
                    if (!active) {
                        return;
                    }

                    kakaoReadyRef.current =
                        true;

                    initializeMap(
                        mapRef,
                        mapInstanceRef
                    );
                }
            );
        }

        function handleError() {
            if (active) {
                setError(
                    "카카오맵 SDK를 불러오지 못했습니다."
                );
            }
        }

        const key =
            import.meta.env
                .VITE_KAKAO_MAP_JS_KEY;

        if (!key) {
            Promise.resolve().then(
                () => {
                    if (active) {
                        setError(
                            "VITE_KAKAO_MAP_JS_KEY가 설정되지 않았습니다."
                        );
                    }
                }
            );
        } else if (
            window.kakao?.maps
        ) {
            handleLoad();
        } else {
            script =
                document.querySelector(
                    'script[data-smart-ticketing-kakao-map="true"]'
                );

            const existing =
                Boolean(script);

            script ??=
                document.createElement(
                    "script"
                );

            script.addEventListener(
                "load",
                handleLoad
            );

            script.addEventListener(
                "error",
                handleError
            );

            if (!existing) {
                script.setAttribute(
                    "data-smart-ticketing-kakao-map",
                    "true"
                );

                script.src =
                    `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${key}&autoload=false&libraries=services`;

                script.async = true;

                document.head.appendChild(
                    script
                );
            }
        }

        return () => {
            active = false;

            script?.removeEventListener(
                "load",
                handleLoad
            );

            script?.removeEventListener(
                "error",
                handleError
            );

            theaterMarkersRef.current.forEach(
                (marker) =>
                    marker.setMap(null)
            );

            theaterOverlaysRef.current.forEach(
                (overlay) =>
                    overlay.setMap(null)
            );

            currentMarkerRef.current?.setMap(
                null
            );

            currentCircleRef.current?.setMap(
                null
            );
        };
    }, []);

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
            async (
                position
            ) => {
                const latitude =
                    position.coords
                        .latitude;

                const longitude =
                    position.coords
                        .longitude;

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
                    setLocationLoading(
                        false
                    );
                }
            },
            (geoError) => {
                setLocationLoading(
                    false
                );

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
                enableHighAccuracy:
                    true,

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

        if (!mapInstanceRef.current) {
            initializeMap(
                mapRef,
                mapInstanceRef
            );
        }

        if (!mapInstanceRef.current) {
            throw new Error(
                "카카오맵을 초기화하지 못했습니다."
            );
        }

        mapInstanceRef.current.setCenter(
            position
        );

        mapInstanceRef.current.setLevel(
            6
        );

        moveLocationMarker(
            latitude,
            longitude,
            "현재 위치"
        );

        const geocoder =
            new kakao.maps.services.Geocoder();

        const addressResult =
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
                                kakao.maps
                                    .services
                                    .Status
                                    .OK
                            ) {
                                reject(
                                    new Error(
                                        "현재 위치의 주소를 가져오지 못했습니다."
                                    )
                                );

                                return;
                            }

                            resolve(
                                result
                            );
                        }
                    );
                }
            );

        const first =
            addressResult?.[0];

        const roadAddress =
            first?.road_address
                ?.address_name;

        const jibunAddress =
            first?.address
                ?.address_name;

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
            longitude,
            resolvedAddress
        );
    }

    function moveLocationMarker(
        latitude,
        longitude,
        title
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

        setLocationSource(
            title === "현재 위치"
                ? "CURRENT"
                : "MAP"
        );

        if (!mapInstanceRef.current) {
            initializeMap(
                mapRef,
                mapInstanceRef
            );
        }

        if (!mapInstanceRef.current) {
            return;
        }

        if (currentMarkerRef.current) {
            currentMarkerRef.current.setMap(
                null
            );
        }

        currentMarkerRef.current =
            new kakao.maps.Marker({
                map:
                    mapInstanceRef.current,
                position,
                title,
                draggable: true,
            });

        kakao.maps.event.addListener(
            currentMarkerRef.current,
            "dragend",
            () => {
                const markerPosition =
                    currentMarkerRef.current.getPosition();

                moveLocationMarker(
                    markerPosition.getLat(),
                    markerPosition.getLng(),
                    "지도 선택 위치"
                );
            }
        );

        if (currentCircleRef.current) {
            currentCircleRef.current.setMap(
                null
            );
        }

        currentCircleRef.current =
            new kakao.maps.Circle({
                map:
                    mapInstanceRef.current,
                center: position,
                radius: 10000,
                strokeWeight: 2,
                strokeColor: "#222222",
                strokeOpacity: 0.7,
                strokeStyle: "solid",
                fillColor: "#555555",
                fillOpacity: 0.08,
            });

        mapInstanceRef.current.setCenter(
            position
        );
    }

    async function searchSelectedLocation() {
        if (!location) {
            setError(
                "지도에서 위치를 선택해주세요."
            );
            return;
        }

        setError("");
        setMessage("");
        setLocationLoading(true);

        try {
            const kakao =
                window.kakao;

            const geocoder =
                new kakao.maps.services.Geocoder();

            const addressResult =
                await new Promise(
                    (resolve, reject) => {
                        geocoder.coord2Address(
                            location.longitude,
                            location.latitude,
                            (result, status) => {
                                if (
                                    status !==
                                    kakao.maps.services.Status.OK
                                ) {
                                    reject(
                                        new Error(
                                            "선택한 위치의 주소를 가져오지 못했습니다."
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

            const resolvedAddress =
                first?.road_address?.address_name ??
                first?.address?.address_name;

            if (!resolvedAddress) {
                throw new Error(
                    "선택한 위치에서 주소를 확인하지 못했습니다."
                );
            }

            setAddress(
                resolvedAddress
            );

            await loadNearbyTheaters(
                location.latitude,
                location.longitude,
                resolvedAddress
            );

            setMessage(
                "선택한 위치 기준으로 주변 영화관을 조회했습니다."
            );
        } catch (e) {
            setError(
                e.message ??
                "선택한 위치 조회에 실패했습니다."
            );
        } finally {
            setLocationLoading(
                false
            );
        }
    }

    async function loadNearbyTheaters(
        latitude,
        longitude,
        resolvedAddress
    ) {
        setTheaterLoading(
            true
        );

        setError("");

        try {
            const data =
                await theaterApi.nearby({
                    address: resolvedAddress,
                    latitude,
                    longitude,
                    radius: 10000,
                });

            if (
                !Array.isArray(
                    data
                )
            ) {
                throw new Error(
                    "주변 영화관 데이터 형식이 올바르지 않습니다."
                );
            }

            const normalized =
                data.map(
                    (
                        theater
                    ) => {
                        const transitMinutes =
                            toNullableNumber(
                                theater.transitMinutes
                            );

                        const transitDistance =
                            toNullableNumber(
                                theater.transitDistance
                            );

                        const walkMinutes =
                            toNullableNumber(
                                theater.walkMinutes
                            );

                        const walkDistance =
                            toNullableNumber(
                                theater.walkDistance
                            );

                        return {
                            ...theater,

                            transitMinutes,

                            transitDistance,

                            walkMinutes,

                            walkDistance,
                        };
                    }
                );

            normalized.sort(
                (
                    a,
                    b
                ) => {
                    if (
                        a.transitMinutes !=
                        null &&
                        b.transitMinutes !=
                        null
                    ) {
                        return (
                            a.transitMinutes -
                            b.transitMinutes
                        );
                    }

                    if (
                        a.transitMinutes !=
                        null
                    ) {
                        return -1;
                    }

                    if (
                        b.transitMinutes !=
                        null
                    ) {
                        return 1;
                    }

                    return (
                        Number(
                            a.distance ??
                            0
                        ) -
                        Number(
                            b.distance ??
                            0
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

            setTheaters([]);

            renderTheaterMarkers(
                []
            );
        } finally {
            setTheaterLoading(
                false
            );
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
            (
                marker
            ) =>
                marker.setMap(
                    null
                )
        );

        theaterOverlaysRef.current.forEach(
            (
                overlay
            ) =>
                overlay.setMap(
                    null
                )
        );

        theaterMarkersRef.current =
            [];

        theaterOverlaysRef.current =
            [];

        theaterList.forEach(
            (
                theater,
                index
            ) => {
                if (
                    theater.latitude ==
                    null ||
                    theater.longitude ==
                    null
                ) {
                    return;
                }

                const position =
                    new window.kakao.maps.LatLng(
                        Number(
                            theater.latitude
                        ),
                        Number(
                            theater.longitude
                        )
                    );

                const marker =
                    new window.kakao.maps.Marker(
                        {
                            map:
                            mapInstanceRef.current,

                            position,

                            title:
                            theater.name,
                        }
                    );

                const transitMinutes =
                    theater.transitMinutes;

                const walkMinutes =
                    theater.walkMinutes;

                const transitText =
                    transitMinutes !=
                    null
                        ? `대중교통 ${transitMinutes}분`
                        : "";

                const walkText =
                    walkMinutes !=
                    null
                        ? `도보 ${walkMinutes}분`
                        : "";

                const routeText =
                    [
                        transitText,
                        walkText,
                    ]
                        .filter(
                            Boolean
                        )
                        .join(
                            " · "
                        );

                const overlay =
                    new window.kakao.maps.CustomOverlay(
                        {
                            map:
                            mapInstanceRef.current,

                            position,

                            content: `
                                <div class="map-theater-label">
                                    ${index + 1}위
                                    ${
                                routeText
                                    ? ` · ${routeText}`
                                    : ""
                            }
                                </div>
                            `,

                            yAnchor: 1.8,
                        }
                    );

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
            (
                current
            ) => {
                if (
                    current.includes(
                        theaterId
                    )
                ) {
                    setError("");

                    return current.filter(
                        (
                            id
                        ) =>
                            id !==
                            theaterId
                    );
                }

                if (
                    current.length >=
                    5
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

    function toggleSeatPosition(position) {
        const alreadySelected =
            selectedSeats.some(
                (seat) => seat.position === position
            );

        if (alreadySelected) {
            setSelectedSeats(
                (current) =>
                    current
                        .filter(
                            (seat) =>
                                seat.position !== position
                        )
                        .map((seat, index) => ({
                            ...seat,
                            priority: index + 1,
                        }))
            );

            setError("");
            return;
        }

        if (selectedSeats.length >= 3) {
            setError(
                "선호 좌석 위치는 최대 3개까지 선택할 수 있습니다."
            );
            return;
        }

        setSelectedSeats(
            (current) => [
                ...current,
                {
                    position,
                    priority: current.length + 1,
                },
            ]
        );

        setError("");
    }

    function isSeatPositionSelected(position) {
        return selectedSeats.some(
            (seat) => seat.position === position
        );
    }

    function isSeatPositionHovered(position) {
        return (
            hoveredSeatPosition === position
        );
    }

    function getSelectedTheatersInPriorityOrder() {
        // 사용자가 클릭한 순서를 그대로 우선순위로 사용한다.
        // 주변 영화관 목록의 거리/대중교통 정렬은 표시 순서일 뿐
        // 선호 영화관 우선순위를 변경하지 않는다.
        return selectedTheaters
            .map((theaterId) => Number(theaterId))
            .filter((theaterId) => Number.isFinite(theaterId));
    }

    async function save() {
        setError("");
        setMessage("");

        if (!birthDate) {
            setError(
                "생년월일을 입력해주세요."
            );

            return;
        }

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
            selectedTheaters.length <
            3 ||
            selectedTheaters.length >
            5
        ) {
            setError(
                "선호 영화관은 3~5곳을 선택해주세요."
            );

            return;
        }

        if (
            selectedSeats.length <
            1 ||
            selectedSeats.length >
            6
        ) {
            setError(
                "선호 좌석 위치를 1~6개 선택해주세요."
            );

            return;
        }

        const theaterIds =
            getSelectedTheatersInPriorityOrder();

        if (
            theaterIds.length !==
            selectedTheaters.length
        ) {
            setError(
                "현재 조회된 영화관 정보를 확인한 뒤 다시 선택해주세요."
            );

            return;
        }

        setSaving(true);

        try {
            const data =
                await userApi.update({
                    nickname:
                    user.nickname,

                    birthDate,

                    address,

                    preferredTheaterIds:
                    theaterIds,

                    preferredSeatPositions:
                        selectedSeats.map(
                            (
                                seat
                            ) =>
                                seat.position
                        ),
                });

            onSaved(
                data
            );
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

            {!user.birthDate && (
                <section>
                    <h2>
                        생년월일
                    </h2>

                    <p className="help">
                        소셜 로그인 사용자는
                        생년월일을 입력해주세요.
                    </p>

                    <label>
                        생년월일
                    </label>

                    <input
                        type="date"
                        value={
                            birthDate
                        }
                        onChange={(
                            e
                        ) =>
                            setBirthDate(
                                e.target.value
                            )
                        }
                        required
                    />
                </section>
            )}

            <section>
                <h2>
                    거주지 설정
                </h2>

                <p className="help">
                    현재 위치를 기준으로
                    거주지를 자동 확인하고
                    주변 10km 영화관을
                    조회합니다.
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
                    {
                        locationLoading
                            ? "현재 위치 확인 중..."
                            : "현재 위치로 조회"
                    }
                </button>

                <div
                    ref={mapRef}
                    className="kakao-map"
                />

                <p className="help">
                    지도에서 원하는 위치를 클릭하거나
                    마커를 드래그한 뒤 아래 버튼으로
                    해당 위치의 주변 영화관을 조회할 수 있습니다.
                </p>

                <button
                    type="button"
                    className="secondary-button"
                    onClick={
                        searchSelectedLocation
                    }
                    disabled={
                        !location ||
                        locationLoading ||
                        theaterLoading
                    }
                >
                    {
                        locationLoading
                            ? "위치 확인 중..."
                            : "이 위치에서 영화관 조회"
                    }
                </button>

                {location && (
                    <div className="location-info">
                        현재 위치를
                        확인했습니다.
                    </div>
                )}

                {address && (
                    <div className="selected-address">
                        <span>
                            자동으로 확인된 거주지
                        </span>

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
                        {
                            selectedTheaters.length
                        }
                        /5
                    </span>
                </div>

                <p className="help">
                    현재 위치 기준 10km
                    이내의 영화관을
                    대중교통 소요시간순으로
                    표시합니다.
                </p>

                {theaterLoading && (
                    <p className="help">
                        영화관을 조회하는 중...
                    </p>
                )}

                {!theaterLoading &&
                    theaters.length === 0 &&
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
                                    Number(theater.theaterId)
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
                                            Number(theater.theaterId)
                                        )
                                    }
                                >
                                    <div className="theater-rank">
                                        {index + 1}
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

                                        <div>
                                            <strong>
                                                {
                                                    theater.transitMinutes !=
                                                    null
                                                        ? `${theater.transitMinutes}분`
                                                        : "-"
                                                }
                                            </strong>

                                            <small>
                                                대중교통
                                            </small>

                                            {theater.transitDistance !=
                                                null && (
                                                    <small>
                                                        {(
                                                            theater.transitDistance /
                                                            1000
                                                        ).toFixed(
                                                            1
                                                        )}
                                                        km
                                                    </small>
                                                )}
                                        </div>

                                        <div>
                                            <strong>
                                                {
                                                    theater.walkMinutes !=
                                                    null
                                                        ? `${theater.walkMinutes}분`
                                                        : "-"
                                                }
                                            </strong>

                                            <small>
                                                도보
                                            </small>

                                            {theater.walkDistance !=
                                                null && (
                                                    <small>
                                                        {(
                                                            theater.walkDistance /
                                                            1000
                                                        ).toFixed(
                                                            1
                                                        )}
                                                        km
                                                    </small>
                                                )}
                                        </div>

                                    </div>
                                </button>
                            );
                        }
                    )}
                </div>
            </section>

            <section>
                <div className="section-title">
                    <h2>
                        선호 좌석
                    </h2>

                    <span>
                        {selectedSeats.length}/3
                    </span>
                </div>

                <p className="help">
                    실제 영화관 좌석 배치처럼 표시됩니다.
                    좌측/우측의 같은 번호 사이드는 하나의 영역으로 취급합니다.
                    예를 들어 1번에 마우스를 올리면 좌측 1번과 우측 1번이 함께 반응하고,
                    중앙 4·5·6번은 각각 독립적으로 반응합니다.
                    선호 위치는 3개까지 선택하고 선택한 순서가 우선순위가 됩니다.
                </p>

                <div className="screen preference-screen">
                    SCREEN
                </div>

                <div className="theater-seat-map">
                    <div className="seat-column-labels" aria-hidden="true">
                        <span>사이드</span>
                        <span></span>
                        <span>중앙</span>
                        <span></span>
                        <span>사이드</span>
                    </div>

                    <div className="preference-seat-grid">
                        {ROWS.map((row, rowIndex) => (
                            <div className="preference-seat-row" key={row}>
                                <div className="preference-seat-cluster side-left">
                                    {[1, 2, 3].map((number) => (
                                        <span
                                            className="preference-real-seat"
                                            key={number}
                                        >
                                            {row}{number}
                                        </span>
                                    ))}
                                </div>

                                <div className="preference-seat-aisle" />

                                <div className="preference-seat-cluster center">
                                    {[4, 5, 6, 7, 8, 9].map((number) => (
                                        <span
                                            className="preference-real-seat"
                                            key={number}
                                        >
                                            {row}{number}
                                        </span>
                                    ))}
                                </div>

                                <div className="preference-seat-aisle" />

                                <div className="preference-seat-cluster side-right">
                                    {[10, 11, 12].map((number) => (
                                        <span
                                            className="preference-real-seat"
                                            key={number}
                                        >
                                            {row}{number}
                                        </span>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>

                    <div className="seat-zone-overlay">
                        {[
                            { side: "left", position: "SIDE_FRONT", rowStart: 1, rowSpan: 3 },
                            { side: "middle", position: "MIDDLE_FRONT", rowStart: 1, rowSpan: 3 },
                            { side: "right", position: "SIDE_FRONT", rowStart: 1, rowSpan: 3 },

                            { side: "left", position: "SIDE_MIDDLE", rowStart: 4, rowSpan: 4 },
                            { side: "middle", position: "MIDDLE_MIDDLE", rowStart: 4, rowSpan: 4 },
                            { side: "right", position: "SIDE_MIDDLE", rowStart: 4, rowSpan: 4 },

                            { side: "left", position: "SIDE_REAR", rowStart: 8, rowSpan: 3 },
                            { side: "middle", position: "MIDDLE_REAR", rowStart: 8, rowSpan: 3 },
                            { side: "right", position: "SIDE_REAR", rowStart: 8, rowSpan: 3 },
                        ].map((zone) => {
                            const selected =
                                isSeatPositionSelected(zone.position);

                            const hovered =
                                isSeatPositionHovered(zone.position);

                            const priority =
                                selectedSeats.find(
                                    (seat) =>
                                        seat.position === zone.position
                                )?.priority;

                            return (
                                <button
                                    type="button"
                                    key={zone.side + zone.position}
                                    className={[
                                        "seat-zone-button",
                                        zone.side,
                                        selected ? "selected" : "",
                                        hovered ? "hovered" : "",
                                    ]
                                        .filter(Boolean)
                                        .join(" ")}
                                    style={{
                                        gridColumn:
                                            zone.side === "left"
                                                ? "1 / 4"
                                                : zone.side === "middle"
                                                    ? "5 / 11"
                                                    : "12 / 15",
                                        gridRow:
                                            zone.rowStart +
                                            " / span " +
                                            zone.rowSpan,
                                    }}
                                    onClick={() =>
                                        toggleSeatPosition(zone.position)
                                    }
                                    onMouseEnter={() =>
                                        setHoveredSeatPosition(
                                            zone.position
                                        )
                                    }
                                    onMouseLeave={() =>
                                        setHoveredSeatPosition(null)
                                    }
                                    aria-pressed={selected}
                                    aria-label={getSeatLabel(zone.position)}
                                >
                                    {priority && (
                                        <strong>
                                            {priority}순위
                                        </strong>
                                    )}
                                </button>
                            );
                        })}
                    </div>
                </div>

                <div className="seat-selection-summary">
                    {selectedSeats.length === 0 ? (
                        <span className="empty-selection">
                            선택된 선호 좌석 위치가 없습니다.
                        </span>
                    ) : (
                        selectedSeats.map((seat) => (
                            <div
                                key={seat.position}
                                className="seat-summary-item"
                            >
                                <strong>
                                    {seat.priority}위
                                </strong>

                                <span>
                                    {getSeatLabel(seat.position)}
                                </span>
                            </div>
                        ))
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
                className="primary-button save-preference-button"
                onClick={
                    save
                }
                disabled={
                    saving
                }
            >
                {
                    saving
                        ? "저장 중..."
                        : "선호 정보 저장"
                }
            </button>

        </div>
    );
}
