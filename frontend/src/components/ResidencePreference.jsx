import {
    useEffect,
    useRef,
    useState,
} from "react";

import { theaterApi, userApi } from "../api";

const ROWS =
    "ABCDEFGHIJ".split("");

const SEAT_POSITION_LABELS = {
    SIDE_FRONT: "양옆 1번",
    SIDE_MIDDLE: "양옆 2번",
    SIDE_REAR: "양옆 3번",
    MIDDLE_FRONT: "중앙 4번",
    MIDDLE_MIDDLE: "중앙 5번",
    MIDDLE_REAR: "중앙 6번",
};

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
        .slice(0, 6);
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

    const map = new kakao.maps.Map(
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

    mapInstanceRef.current = map;

    // 위치 선택 화면에서는 줌 컨트롤만 깔끔하게 유지합니다.
    map.addControl(
        new kakao.maps.ZoomControl(),
        kakao.maps.ControlPosition.RIGHT
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

    const birthYearRef = useRef(null);
    const birthMonthRef = useRef(null);
    const birthDayRef = useRef(null);

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

    const theaterMarkerImageCacheRef =
        useRef(new Map());

    const theaterLogoDataCacheRef =
        useRef(new Map());

    const kakaoReadyRef =
        useRef(false);

    const isMapSelectionModeRef =
        useRef(false);

    const initialBirthDate = user.birthDate ?? "";
    const initialBirthParts = initialBirthDate.split("-");

    const [birthYear, setBirthYear] = useState(initialBirthParts[0] ?? "");
    const [birthMonth, setBirthMonth] = useState(initialBirthParts[1] ?? "");
    const [birthDay, setBirthDay] = useState(initialBirthParts[2] ?? "");

    const birthDate =
        birthYear.length === 4 && birthMonth.length === 2 && birthDay.length === 2
            ? birthYear + "-" + birthMonth + "-" + birthDay
            : "";

    const [address, setAddress] =
        useState(
            user.address ?? ""
        );

    const [location, setLocation] =
        useState(null);

    const [locationSource, setLocationSource] =
        useState(null);

    const [isMapSelectionMode, setIsMapSelectionMode] =
        useState(false);

    const [theaters, setTheaters] =
        useState([]);

    const [theaterSort, setTheaterSort] =
        useState("DISTANCE");

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

    const [currentLocationLoading, setCurrentLocationLoading] =
        useState(false);

    const [mapSearchLoading, setMapSearchLoading] =
        useState(false);

    const [placeSearchKeyword, setPlaceSearchKeyword] =
        useState("");

    const [placeSearchResults, setPlaceSearchResults] =
        useState([]);

    const [placeSearchLoading, setPlaceSearchLoading] =
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
        const updateTheaterMarqueeWidths = () => {
            document
                .querySelectorAll(".theater-list .theater-name-marquee")
                .forEach((element) => {
                    const overflow = Math.max(
                        0,
                        element.scrollWidth - element.clientWidth
                    );

                    element.style.setProperty(
                        "--marquee-shift",
                        overflow + "px"
                    );
                    element.classList.toggle(
                        "is-overflowing",
                        overflow > 1
                    );
                });
        };

        updateTheaterMarqueeWidths();
        window.addEventListener("resize", updateTheaterMarqueeWidths);

        return () => {
            window.removeEventListener("resize", updateTheaterMarqueeWidths);
        };
    }, [theaters, selectedTheaters]);

    useEffect(() => {
        let active = true;
        let script;
        let dragMap;

        async function updateSelectedMapCenter() {
            const map = mapInstanceRef.current;

            if (!map || !isMapSelectionModeRef.current) {
                return;
            }

            const center = map.getCenter();
            const latitude = center.getLat();
            const longitude = center.getLng();

            setLocation({
                latitude,
                longitude,
            });
            setLocationSource("MAP");

            try {
                const kakao = window.kakao;
                const geocoder =
                    new kakao.maps.services.Geocoder();

                const addressResult = await new Promise(
                    (resolve, reject) => {
                        geocoder.coord2Address(
                            longitude,
                            latitude,
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

                const first = addressResult?.[0];
                const resolvedAddress =
                    first?.road_address?.address_name ??
                    first?.address?.address_name;

                if (resolvedAddress) {
                    setAddress(resolvedAddress);
                }
            } catch (e) {
                setError(
                    e.message ??
                    "선택한 위치의 주소를 확인하지 못했습니다."
                );
            }
        }

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

                    dragMap = mapInstanceRef.current;
                    window.kakao.maps.event.addListener(
                        dragMap,
                        "dragend",
                        updateSelectedMapCenter
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

            if (dragMap) {
                window.kakao.maps.event.removeListener(
                    dragMap,
                    "dragend",
                    updateSelectedMapCenter
                );
            }

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
        setCurrentLocationLoading(true);

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
                    setCurrentLocationLoading(
                        false
                    );
                }
            },
            (geoError) => {
                setCurrentLocationLoading(
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

    function createCurrentLocationMarker(position, title = "현재 위치") {
        const kakao = window.kakao;

        const svg =
            '<svg xmlns="http://www.w3.org/2000/svg" width="38" height="48" viewBox="0 0 38 48">' +
            '<path d="M19 1C9.06 1 1 9.06 1 19c0 13.2 18 28 18 28s18-14.8 18-28C37 9.06 28.94 1 19 1z" fill="#e53935" stroke="#ffffff" stroke-width="3"/>' +
            '<circle cx="19" cy="19" r="7" fill="#ffffff"/>' +
            '</svg>';

        const image = new kakao.maps.MarkerImage(
            "data:image/svg+xml;charset=UTF-8," + encodeURIComponent(svg),
            new kakao.maps.Size(38, 48),
            {
                offset: new kakao.maps.Point(19, 48),
            }
        );

        return new kakao.maps.Marker({
            map: mapInstanceRef.current,
            position,
            title,
            image,
            zIndex: 1000,
        });
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

        clearMapSearchVisuals();
        setIsMapSelectionMode(false);
        isMapSelectionModeRef.current = false;
        showSelectionPin(false);

        currentMarkerRef.current =
            createCurrentLocationMarker(
                position
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
            resolvedAddress,
            true
        );
    }

    function showSelectionPin(show) {
        if (!mapRef.current) {
            return;
        }

        mapRef.current.classList.toggle(
            "map-selection-active",
            show
        );
    }

    function clearMapSearchVisuals() {
        theaterMarkersRef.current.forEach((marker) =>
            marker.setMap(null)
        );
        theaterMarkersRef.current = [];

        theaterOverlaysRef.current.forEach((overlay) =>
            overlay.setMap(null)
        );
        theaterOverlaysRef.current = [];

        currentMarkerRef.current?.setMap(null);
        currentMarkerRef.current = null;

        currentCircleRef.current?.setMap(null);
        currentCircleRef.current = null;
    }

    function startMapSelection() {
        if (!kakaoReadyRef.current) {
            setError("카카오맵이 아직 준비되지 않았습니다.");
            return;
        }

        const map = mapInstanceRef.current;

        if (!map) {
            setError("카카오맵을 초기화하지 못했습니다.");
            return;
        }

        setError("");
        setMessage("");
        setIsMapSelectionMode(true);
        isMapSelectionModeRef.current = true;
        clearMapSearchVisuals();
        showSelectionPin(true);

        if (location) {
            map.setCenter(
                new window.kakao.maps.LatLng(
                    location.latitude,
                    location.longitude
                )
            );
        } else {
            map.setCenter(
                new window.kakao.maps.LatLng(
                    37.5665,
                    126.978
                )
            );
            map.setLevel(7);
        }

        const center = map.getCenter();

        setLocation({
            latitude: center.getLat(),
            longitude: center.getLng(),
        });
        setLocationSource("MAP");
    }

    function searchPlaces() {
        const keyword = placeSearchKeyword.trim();

        if (!keyword) {
            setPlaceSearchResults([]);
            setError("검색할 장소나 주소를 입력해주세요.");
            return;
        }

        if (!kakaoReadyRef.current) {
            setError("카카오맵이 아직 준비되지 않았습니다.");
            return;
        }

        const kakao = window.kakao;
        const places = new kakao.maps.services.Places();

        setError("");
        setMessage("");
        setPlaceSearchLoading(true);

        places.keywordSearch(
            keyword,
            (data, status) => {
                setPlaceSearchLoading(false);

                if (
                    status ===
                    kakao.maps.services.Status.ZERO_RESULT
                ) {
                    setPlaceSearchResults([]);
                    setError("검색 결과가 없습니다.");
                    return;
                }

                if (
                    status !==
                    kakao.maps.services.Status.OK
                ) {
                    setPlaceSearchResults([]);
                    setError("장소 검색에 실패했습니다.");
                    return;
                }

                setPlaceSearchResults(data.slice(0, 5));
                setIsMapSelectionMode(true);
                isMapSelectionModeRef.current = true;
                clearMapSearchVisuals();
                showSelectionPin(true);
            }
        );
    }

    async function selectPlaceSearchResult(place) {
        const map = mapInstanceRef.current;

        if (!map) {
            setError("카카오맵을 초기화하지 못했습니다.");
            return;
        }

        const latitude = Number(place.y);
        const longitude = Number(place.x);

        if (
            !Number.isFinite(latitude) ||
            !Number.isFinite(longitude)
        ) {
            setError("검색한 장소의 위치 정보를 확인하지 못했습니다.");
            return;
        }

        const position =
            new window.kakao.maps.LatLng(
                latitude,
                longitude
            );

        map.setCenter(position);
        map.setLevel(5);

        setLocation({
            latitude,
            longitude,
        });
        setLocationSource("MAP");

        const resolvedAddress =
            place.road_address_name ||
            place.address_name ||
            place.place_name;

        setAddress(resolvedAddress);
        setPlaceSearchResults([]);
        setError("");
        setMessage("");
        setIsMapSelectionMode(false);
        showSelectionPin(false);

        await loadNearbyTheaters(
            latitude,
            longitude,
            resolvedAddress
        );

        setMessage(
            "검색한 위치 기준으로 주변 영화관을 조회했습니다."
        );
    }

    function finishMapSelection() {
        searchSelectedLocation();
    }

    async function searchSelectedLocation() {
        const map = mapInstanceRef.current;

        let selectedLocation = location;

        if (isMapSelectionMode && map) {
            const center = map.getCenter();

            selectedLocation = {
                latitude: center.getLat(),
                longitude: center.getLng(),
            };

            setLocation(selectedLocation);
            setLocationSource("MAP");
        }

        if (!selectedLocation) {
            setError(
                "지도에서 위치를 선택해주세요."
            );
            return;
        }

        setError("");
        setMessage("");
        setMapSearchLoading(true);

        try {
            const kakao =
                window.kakao;

            const geocoder =
                new kakao.maps.services.Geocoder();

            const addressResult =
                await new Promise(
                    (resolve, reject) => {
                        geocoder.coord2Address(
                            selectedLocation.longitude,
                            selectedLocation.latitude,
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

            // 조회 기준점을 다시 지도 중앙에 맞춰 마커가 바로 보이도록 합니다.
            if (map) {
                map.setCenter(
                    new window.kakao.maps.LatLng(
                        selectedLocation.latitude,
                        selectedLocation.longitude
                    )
                );
                map.setLevel(6);
            }

            await loadNearbyTheaters(
                selectedLocation.latitude,
                selectedLocation.longitude,
                resolvedAddress
            );

            setIsMapSelectionMode(false);
            isMapSelectionModeRef.current = false;
            showSelectionPin(false);

            setMessage(
                "선택한 위치 기준으로 주변 영화관을 조회했습니다."
            );
        } catch (e) {
            setError(
                e.message ??
                "선택한 위치 조회에 실패했습니다."
            );
        } finally {
            setMapSearchLoading(
                false
            );
        }
    }

    async function loadNearbyTheaters(
        latitude,
        longitude,
        resolvedAddress,
        showCurrentLocationMarker = false
    ) {
        setSelectedTheaters([]);
        setTheaterLoading(true);
        clearMapSearchVisuals();
        setError("");
        setTheaterSort("DISTANCE");

        try {
            const data = await theaterApi.nearby({
                address: resolvedAddress,
                latitude,
                longitude,
                sort: "DISTANCE",
            });

            if (!Array.isArray(data)) {
                throw new Error(
                    "주변 영화관 데이터 형식이 올바르지 않습니다."
                );
            }

            const normalized = data.map((theater) => ({
                ...theater,
                distance: toNullableNumber(theater.distance),
                transitMinutes: toNullableNumber(theater.transitMinutes),
                transitDistance: toNullableNumber(theater.transitDistance),
                walkMinutes: toNullableNumber(theater.walkMinutes),
                walkDistance: toNullableNumber(theater.walkDistance),
            }));

            setTheaters(normalized);

            // 영화관 마커를 그린 뒤에도 현재 위치 마커를 유지합니다.
            if (
                showCurrentLocationMarker &&
                mapInstanceRef.current &&
                window.kakao?.maps
            ) {
                currentMarkerRef.current?.setMap(null);
                currentMarkerRef.current =
                    createCurrentLocationMarker(
                        new window.kakao.maps.LatLng(
                            latitude,
                            longitude
                        )
                    );
            }
        } catch (e) {
            setError(
                e.message ??
                "주변 영화관 조회에 실패했습니다."
            );
            setTheaters([]);
        } finally {
            setTheaterLoading(false);
        }
    }


    useEffect(() => {
        function getTheaterBrand(
            theater
        ) {
            const brand = String(
                theater.brand ?? ""
            ).toUpperCase();

            return brand === "CGV"
                ? "CGV"
                : brand === "LOTTE_CINEMA"
                    ? "LOTTE_CINEMA"
                    : "MEGABOX";
        }

        function getTheaterLogoUrl(
            brand
        ) {
            return (
                "https://raw.githubusercontent.com/ahnjh97/SmartTicketing/feature/jusang/" +
                brand +
                ".png"
            );
        }

        function createTransparentMarkerImage(
            markerSize
        ) {
            const svg =
                '<svg xmlns="http://www.w3.org/2000/svg" width="' +
                markerSize +
                '" height="' +
                markerSize +
                '" viewBox="0 0 ' +
                markerSize +
                " " +
                markerSize +
                '"><rect width="' +
                markerSize +
                '" height="' +
                markerSize +
                '" fill="transparent"/></svg>';

            return new window.kakao.maps.MarkerImage(
                "data:image/svg+xml;charset=UTF-8," +
                    encodeURIComponent(svg),
                new window.kakao.maps.Size(
                    markerSize,
                    markerSize
                ),
                {
                    offset: new window.kakao.maps.Point(
                        markerSize / 2,
                        markerSize / 2
                    ),
                }
            );
        }

        function loadTheaterLogoData(
            brand
        ) {
            const cached =
                theaterLogoDataCacheRef.current.get(
                    brand
                );

            if (cached) {
                return cached;
            }

            const promise = new Promise(
                (resolve, reject) => {
                    const image = new Image();
                    image.crossOrigin = "anonymous";

                    image.onload = () => {
                        try {
                            const sourceWidth =
                                image.naturalWidth;
                            const sourceHeight =
                                image.naturalHeight;

                            if (
                                !sourceWidth ||
                                !sourceHeight
                            ) {
                                reject(
                                    new Error(
                                        "영화관 로고 이미지 크기를 확인하지 못했습니다."
                                    )
                                );
                                return;
                            }

                            const canvas =
                                document.createElement("canvas");
                            canvas.width = sourceWidth;
                            canvas.height = sourceHeight;

                            const context =
                                canvas.getContext("2d", {
                                    willReadFrequently: true,
                                });

                            if (!context) {
                                reject(
                                    new Error(
                                        "영화관 로고 이미지를 처리하지 못했습니다."
                                    )
                                );
                                return;
                            }

                            context.drawImage(
                                image,
                                0,
                                0,
                                sourceWidth,
                                sourceHeight
                            );

                            const imageData =
                                context.getImageData(
                                    0,
                                    0,
                                    sourceWidth,
                                    sourceHeight
                                );

                            const pixels =
                                imageData.data;
                            let minX = sourceWidth;
                            let minY = sourceHeight;
                            let maxX = -1;
                            let maxY = -1;

                            for (
                                let y = 0;
                                y < sourceHeight;
                                y += 1
                            ) {
                                for (
                                    let x = 0;
                                    x < sourceWidth;
                                    x += 1
                                ) {
                                    const index =
                                        (y * sourceWidth + x) * 4;
                                    const red = pixels[index];
                                    const green = pixels[index + 1];
                                    const blue = pixels[index + 2];

                                    const max = Math.max(
                                        red,
                                        green,
                                        blue
                                    );
                                    const min = Math.min(
                                        red,
                                        green,
                                        blue
                                    );
                                    const saturation =
                                        max - min;

                                    if (
                                        saturation < 28 &&
                                        max < 235
                                    ) {
                                        pixels[index + 3] = 0;
                                        continue;
                                    }

                                    if (
                                        pixels[index + 3] > 0
                                    ) {
                                        minX = Math.min(
                                            minX,
                                            x
                                        );
                                        minY = Math.min(
                                            minY,
                                            y
                                        );
                                        maxX = Math.max(
                                            maxX,
                                            x
                                        );
                                        maxY = Math.max(
                                            maxY,
                                            y
                                        );
                                    }
                                }
                            }

                            if (
                                maxX < minX ||
                                maxY < minY
                            ) {
                                reject(
                                    new Error(
                                        "영화관 로고 영역을 찾지 못했습니다."
                                    )
                                );
                                return;
                            }

                            context.putImageData(
                                imageData,
                                0,
                                0
                            );

                            const padding = Math.max(
                                2,
                                Math.round(
                                    Math.max(
                                        maxX - minX + 1,
                                        maxY - minY + 1
                                    ) * 0.04
                                )
                            );
                            const cropX = Math.max(
                                0,
                                minX - padding
                            );
                            const cropY = Math.max(
                                0,
                                minY - padding
                            );
                            const cropRight = Math.min(
                                sourceWidth - 1,
                                maxX + padding
                            );
                            const cropBottom = Math.min(
                                sourceHeight - 1,
                                maxY + padding
                            );
                            const cropWidth =
                                cropRight - cropX + 1;
                            const cropHeight =
                                cropBottom - cropY + 1;
                            const cropSize = Math.max(
                                cropWidth,
                                cropHeight
                            );

                            const cropCanvas =
                                document.createElement("canvas");
                            cropCanvas.width = cropSize;
                            cropCanvas.height = cropSize;

                            const cropContext =
                                cropCanvas.getContext("2d");

                            if (!cropContext) {
                                reject(
                                    new Error(
                                        "영화관 로고를 잘라내지 못했습니다."
                                    )
                                );
                                return;
                            }

                            cropContext.drawImage(
                                canvas,
                                cropX,
                                cropY,
                                cropWidth,
                                cropHeight,
                                (cropSize - cropWidth) / 2,
                                (cropSize - cropHeight) / 2,
                                cropWidth,
                                cropHeight
                            );

                            resolve(
                                cropCanvas.toDataURL(
                                    "image/png"
                                )
                            );
                        } catch (error) {
                            reject(error);
                        }
                    };

                    image.onerror = () =>
                        reject(
                            new Error(
                                "영화관 로고 이미지를 불러오지 못했습니다."
                            )
                        );

                    image.src = getTheaterLogoUrl(
                        brand
                    );
                }
            );

            theaterLogoDataCacheRef.current.set(
                brand,
                promise
            );

            return promise;
        }

        async function getTheaterMarkerImage(
            brand,
            selected,
            markerSize
        ) {
            const cacheKey =
                brand +
                ":" +
                (selected ? "selected" : "normal") +
                ":" +
                markerSize;

            const cached =
                theaterMarkerImageCacheRef.current.get(
                    cacheKey
                );

            if (cached) {
                return cached;
            }

            const logoDataUrl =
                await loadTheaterLogoData(
                    brand
                );

            const canvas =
                document.createElement("canvas");
            const pixelSize =
                Math.round(markerSize * 2);
            canvas.width = pixelSize;
            canvas.height = pixelSize;

            const context =
                canvas.getContext("2d");

            if (!context) {
                throw new Error(
                    "영화관 마커를 생성하지 못했습니다."
                );
            }

            const center =
                pixelSize / 2;
            const radius =
                pixelSize / 2 - 3;
            const borderWidth =
                selected ? 6 : 5;

            context.clearRect(
                0,
                0,
                pixelSize,
                pixelSize
            );

            context.beginPath();
            context.arc(
                center,
                center,
                radius,
                0,
                Math.PI * 2
            );
            context.fillStyle = "#fff";
            context.fill();
            context.lineWidth = borderWidth;
            context.strokeStyle = selected
                ? "#ffc426"
                : "#111";
            context.stroke();

            context.save();
            context.beginPath();
            context.arc(
                center,
                center,
                radius - borderWidth / 2,
                0,
                Math.PI * 2
            );
            context.clip();

            const logoImage =
                await new Promise(
                    (resolve, reject) => {
                        const image = new Image();
                        image.onload = () =>
                            resolve(image);
                        image.onerror = () =>
                            reject(
                                new Error(
                                    "가공된 영화관 로고를 불러오지 못했습니다."
                                )
                            );
                        image.src = logoDataUrl;
                    }
                );

            // 가공된 PNG는 실제 로고 영역만 남겨진 정사각형 이미지이므로
            // 마커 안쪽을 최대한 채우도록 배치합니다.
            // 검정색 작업용 원/회색 배경은 loadTheaterLogoData()에서 제거됩니다.
            const innerSize =
                pixelSize - borderWidth * 2;
            const logoSize =
                innerSize * 1.35;

            context.drawImage(
                logoImage,
                center - logoSize / 2,
                center - logoSize / 2,
                logoSize,
                logoSize
            );
            context.restore();

            const dataUrl =
                canvas.toDataURL("image/png");

            const markerImage =
                new window.kakao.maps.MarkerImage(
                    dataUrl,
                    new window.kakao.maps.Size(
                        markerSize,
                        markerSize
                    ),
                    {
                        offset: new window.kakao.maps.Point(
                            markerSize / 2,
                            markerSize / 2
                        ),
                    }
                );

            theaterMarkerImageCacheRef.current.set(
                cacheKey,
                markerImage
            );

            return markerImage;
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

            theaterMarkersRef.current.forEach((marker) =>
                marker.setMap(null)
            );
            theaterOverlaysRef.current.forEach((overlay) =>
                overlay.setMap(null)
            );

            theaterMarkersRef.current = [];
            theaterOverlaysRef.current = [];

            theaterList.forEach((theater) => {
                if (
                    theater.latitude == null ||
                    theater.longitude == null
                ) {
                    return;
                }

                const position =
                    new window.kakao.maps.LatLng(
                        Number(theater.latitude),
                        Number(theater.longitude)
                    );

                const selected =
                    selectedTheaters.includes(
                        Number(theater.theaterId)
                    );

                const brand =
                    getTheaterBrand(theater);
                const markerSize =
                    selected ? 46 : 42;

                // 실제 로고가 준비되기 전에도 동일한 크기의 투명 MarkerImage를 사용해
                // 마우스 이벤트 영역을 유지합니다. 로고가 준비되면 MarkerImage만 교체합니다.
                const marker =
                    new window.kakao.maps.Marker({
                        map: mapInstanceRef.current,
                        position,
                        image: createTransparentMarkerImage(
                            markerSize
                        ),
                        title: theater.name,
                        zIndex: selected ? 31 : 21,
                    });

                getTheaterMarkerImage(
                    brand,
                    selected,
                    markerSize
                )
                    .then((markerImage) => {
                        if (marker.getMap()) {
                            marker.setImage(
                                markerImage
                            );
                        }
                    })
                    .catch(() => {
                        // 로고 생성에 실패해도 지도와 영화관 위치 표시에는 영향을 주지 않습니다.
                    });

                const distanceText =
                    theater.distance != null
                        ? theater.distance >= 1000
                            ? `${(theater.distance / 1000).toFixed(1)}km`
                            : `${theater.distance}m`
                        : "";

                const createOverlayContent = (below = false) => {
                    const element =
                        document.createElement("div");

                    element.className =
                        below
                            ? "map-theater-info below"
                            : "map-theater-info";

                    const nameElement =
                        document.createElement("strong");

                    nameElement.textContent =
                        theater.name;

                    element.appendChild(
                        nameElement
                    );

                    if (distanceText) {
                        const distanceElement =
                            document.createElement("span");

                        distanceElement.textContent =
                            distanceText;

                        element.appendChild(
                            distanceElement
                        );
                    }

                    return element;
                };

                const overlay =
                    new window.kakao.maps.CustomOverlay({
                        position,
                        content:
                            createOverlayContent(),
                        yAnchor: 1,
                        zIndex: 40,
                    });

                const belowOverlay =
                    new window.kakao.maps.CustomOverlay({
                        position,
                        content:
                            createOverlayContent(true),
                        yAnchor: 0,
                        zIndex: 40,
                    });

                let hideTimer = null;

                const clearHideTimer = () => {
                    if (hideTimer) {
                        window.clearTimeout(
                            hideTimer
                        );

                        hideTimer = null;
                    }
                };

                const scheduleHide = () => {
                    clearHideTimer();

                    hideTimer =
                        window.setTimeout(
                            () => {
                                overlay.setMap(null);
                                belowOverlay.setMap(null);
                                hideTimer = null;
                            },
                            120
                        );
                };

                const overlayElement =
                    overlay.getContent();
                const belowOverlayElement =
                    belowOverlay.getContent();

                overlayElement.addEventListener(
                    "mouseenter",
                    clearHideTimer
                );
                belowOverlayElement.addEventListener(
                    "mouseenter",
                    clearHideTimer
                );
                overlayElement.addEventListener(
                    "mouseleave",
                    scheduleHide
                );
                belowOverlayElement.addEventListener(
                    "mouseleave",
                    scheduleHide
                );

                window.kakao.maps.event.addListener(
                    marker,
                    "mouseover",
                    () => {
                        const map =
                            mapInstanceRef.current;

                        if (!map) {
                            return;
                        }

                        clearHideTimer();

                        const projection =
                            map.getProjection();
                        const point =
                            projection.containerPointFromCoords(
                                position
                            );
                        const showBelow =
                            point.y < 65;

                        if (showBelow) {
                            overlay.setMap(null);
                            belowOverlay.setPosition(
                                position
                            );
                            belowOverlay.setMap(map);
                            belowOverlay.setZIndex(40);
                        } else {
                            belowOverlay.setMap(null);
                            overlay.setPosition(
                                position
                            );
                            overlay.setMap(map);
                            overlay.setZIndex(40);
                        }
                    }
                );

                window.kakao.maps.event.addListener(
                    marker,
                    "mouseout",
                    scheduleHide
                );

                theaterMarkersRef.current.push(
                    marker
                );
                theaterOverlaysRef.current.push(
                    overlay,
                    belowOverlay
                );
            });
        }

        renderTheaterMarkers(theaters);
    }, [selectedTheaters, theaters]);

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

    function handleBirthYearChange(event) {
        const value = event.target.value.replace(/\D/g, "").slice(0, 4);
        setBirthYear(value);
        if (value.length === 4) {
            birthMonthRef.current?.focus();
            birthMonthRef.current?.select();
        }
    }

    function handleBirthMonthChange(event) {
        const value = event.target.value.replace(/\D/g, "").slice(0, 2);
        setBirthMonth(value);
        if (value.length === 2) {
            birthDayRef.current?.focus();
            birthDayRef.current?.select();
        }
    }

    function handleBirthDayChange(event) {
        setBirthDay(event.target.value.replace(/\D/g, "").slice(0, 2));
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

                    <div className="birth-date-input-row">
                        <input ref={birthYearRef} type="text" inputMode="numeric" value={birthYear} onChange={handleBirthYearChange} placeholder="YYYY" maxLength={4} aria-label="생년월일 년도" required />
                        <span>-</span>
                        <input ref={birthMonthRef} type="text" inputMode="numeric" value={birthMonth} onChange={handleBirthMonthChange} placeholder="MM" maxLength={2} aria-label="생년월일 월" required />
                        <span>-</span>
                        <input ref={birthDayRef} type="text" inputMode="numeric" value={birthDay} onChange={handleBirthDayChange} placeholder="DD" maxLength={2} aria-label="생년월일 일" required />
                    </div>
                </section>
            )}

            <section>

                <div className="location-action-buttons">
                    <button
                        type="button"
                        className="primary-button location-current-button"
                        onClick={getCurrentLocation}
                        disabled={currentLocationLoading}
                    >
                        {currentLocationLoading
                            ? "현재 위치 확인 중..."
                            : "⌖ 현재 사용자 위치에서 조회"}
                    </button>

                    <button
                        type="button"
                        className={
                            isMapSelectionMode
                                ? "secondary-button location-map-button active"
                                : "secondary-button location-map-button"
                        }
                        onClick={startMapSelection}
                        disabled={currentLocationLoading}
                    >
                        🗺️ {isMapSelectionMode
                            ? "위치 선택 중"
                            : "다른 위치 선택하기"}
                    </button>
                </div>

                <div className="map-place-search">
                    <div className="map-place-search-row">
                        <input
                            type="text"
                            value={placeSearchKeyword}
                            onChange={(event) =>
                                setPlaceSearchKeyword(
                                    event.target.value
                                )
                            }
                            onKeyDown={(event) => {
                                if (event.key === "Enter") {
                                    event.preventDefault();
                                    searchPlaces();
                                }
                            }}
                            placeholder="주소 또는 장소를 검색하세요"
                            aria-label="주소 또는 장소 검색"
                        />
                        <button
                            type="button"
                            className="map-place-search-button"
                            onClick={searchPlaces}
                            disabled={
                                placeSearchLoading ||
                                currentLocationLoading
                            }
                        >
                            {placeSearchLoading
                                ? "검색 중..."
                                : "검색"}
                        </button>
                    </div>

                    {placeSearchResults.length > 0 && (
                        <div className="map-place-search-results">
                            {placeSearchResults.map((place) => (
                                <button
                                    type="button"
                                    key={place.id}
                                    className="map-place-search-result"
                                    onClick={() =>
                                        selectPlaceSearchResult(place)
                                    }
                                >
                                    <strong>
                                        {place.place_name}
                                    </strong>
                                    <span>
                                        {place.road_address_name ||
                                            place.address_name}
                                    </span>
                                    {place.category_name && (
                                        <small>
                                            {place.category_name}
                                        </small>
                                    )}
                                </button>
                            ))}
                        </div>
                    )}
                </div>

                <div className="map-selection-wrapper">
                    <div
                        ref={mapRef}
                        className="kakao-map"
                    />
                    {isMapSelectionMode && (
                        <div
                            className="map-center-pin"
                            aria-hidden="true"
                        >
                            <span>📍</span>
                        </div>
                    )}
                </div>

                <p className="help map-selection-help">
                    {isMapSelectionMode
                        ? "지도를 드래그하면 중앙 핀 위치가 이동합니다. 원하는 위치에 맞춘 뒤 아래 버튼을 눌러 영화관을 조회하세요."
                        : "다른 위치를 찾으려면 '다른 위치 선택하기'를 누른 뒤 지도를 드래그하세요."}
                </p>

                <button
                    type="button"
                    className="primary-button map-search-button"
                    onClick={finishMapSelection}
                    disabled={
                        !isMapSelectionMode ||
                        mapSearchLoading ||
                        theaterLoading
                    }
                >
                    {mapSearchLoading
                        ? "위치 확인 중..."
                        : "이 위치에서 영화관 조회"}
                </button>

                {address && (
                    <div className="selected-address">
                        <span>
                            {locationSource === "MAP"
                                ? "선택한 위치"
                                : "현재 사용자 위치"}
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
                    선택한 위치를 기준으로 가까운 영화관 12개를 표시합니다.
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
                            {theaterSort === "WALK"
                                ? "거리상 너무 멉니다."
                                : "주변 영화관이 없습니다."}
                        </p>
                    )}

                <div className="theater-list">
                    {theaters.slice(0, 12).map(
                        (
                            theater
                        ) => {
                            const selected =
                                selectedTheaters.includes(
                                    Number(theater.theaterId)
                                );

                            const kakaoDirectionsUrl =
                                location &&
                                theater.latitude != null &&
                                theater.longitude != null
                                    ? "https://map.kakao.com/link/from/" +
                                      (address || "선택한 위치") +
                                      "," +
                                      location.latitude +
                                      "," +
                                      location.longitude +
                                      "/to/" +
                                      theater.name +
                                      "," +
                                      theater.latitude +
                                      "," +
                                      theater.longitude
                                    : "https://map.kakao.com/link/to/" +
                                      theater.name +
                                      "," +
                                      theater.latitude +
                                      "," +
                                      theater.longitude;

                            return (
                                <div
                                    key={theater.theaterId}
                                    className={
                                        selected
                                            ? "theater-item selected"
                                            : "theater-item"
                                    }
                                >
                                    <button
                                        type="button"
                                        className="theater-item-main"
                                        onClick={() =>
                                            toggleTheater(
                                                Number(theater.theaterId)
                                            )
                                        }
                                    >
                                    <div className="theater-main">
                                        <div className="theater-name-marquee">
                                            <strong>
                                                {
                                                    theater.name
                                                }
                                            </strong>
                                        </div>

                                        <small>
                                            {
                                                theater.address
                                            }
                                        </small>
                                    </div>

                                    <div className="theater-time">
                                        {theaterSort === "DISTANCE" && (
                                            <div>
                                                <strong>
                                                    {theater.distance != null
                                                        ? theater.distance >= 1000
                                                            ? `${(theater.distance / 1000).toFixed(1)}km`
                                                            : `${theater.distance}m`
                                                        : "-"}
                                                </strong>
                                                <small>직선거리</small>
                                            </div>
                                        )}

                                        {theaterSort === "TRANSIT" && (
                                            <div>
                                                <strong>
                                                    {theater.transitMinutes != null
                                                        ? `${theater.transitMinutes}분`
                                                        : "-"}
                                                </strong>
                                                <small>대중교통</small>
                                                {theater.transitDistance != null && (
                                                    <small>
                                                        {(theater.transitDistance / 1000).toFixed(1)}km
                                                    </small>
                                                )}
                                            </div>
                                        )}

                                        {theaterSort === "WALK" && (
                                            <div>
                                                <strong>
                                                    {theater.walkMinutes != null
                                                        ? `${theater.walkMinutes}분`
                                                        : "-"}
                                                </strong>
                                                <small>도보</small>
                                                {theater.walkDistance != null && (
                                                    <small>
                                                        {(theater.walkDistance / 1000).toFixed(1)}km
                                                    </small>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                    </button>
                                    {selected ? (
                                        <div className="theater-selected-footer">
                                            <div className="theater-selected-rank">
                                                {(selectedTheaters.indexOf(
                                                    Number(theater.theaterId)
                                                ) + 1) + "순위"}
                                            </div>
                                                                                <a
                                                                                    className="theater-route-button"
                                                                                    href={kakaoDirectionsUrl}
                                                                                    target="_blank"
                                                                                    rel="noreferrer"
                                                                                    onClick={(event) => event.stopPropagation()}
                                                                                >
                                        길찾기
                                    </a>
                                        </div>
                                    ) : (
                                    <a
                                        className="theater-route-button"
                                        href={kakaoDirectionsUrl}
                                        target="_blank"
                                        rel="noreferrer"
                                        onClick={(event) => event.stopPropagation()}
                                    >
                                        길찾기
                                    </a>
                                    )}
                                </div>
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
                        {selectedSeats.length}/6
                    </span>
                </div>

                <p className="help">
                    실제 영화관 좌석 배치처럼 표시됩니다.
                    좌측/우측의 같은 번호 사이드는 하나의 영역으로 취급합니다.
                    예를 들어 1번에 마우스를 올리면 좌측 1번과 우측 1번이 함께 반응하고,
                    중앙 4, 5, 6번은 각각 독립적으로 반응합니다.
                    선호 위치는 1~6개까지 선택하고 선택한 순서가 우선순위가 됩니다.
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
                        {ROWS.map((row) => (
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
