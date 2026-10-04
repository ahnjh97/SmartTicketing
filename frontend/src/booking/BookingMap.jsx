import GlassButton from '../components/GlassButton.jsx';
import styles from './BookingMap.module.css';
import { useEffect, useRef, useState } from 'react';
import { getKakaoMapKey } from '../config/runtime.js';
import { getTheaterBrand, getTheaterMarkerImage } from '../maps/theaterMarkerImages.js';

// Start at Seoul City Hall; request location only when the user presses the button.
export default function BookingMap({ theaters, onSelect }) {
    const container = useRef(null);
    const map = useRef(null);
    const [ready, setReady] = useState(false);
    const [message, setMessage] = useState('극장을 클릭해서 선택해주세요.');
    const [busy, setBusy] = useState(false);
    const sequence = useRef({ value: 0 });
    useEffect(() => {
        let active = true;
        const requestSequence = sequence.current;
        const key = getKakaoMapKey();
        let script;
        const fail = () => { if (active) setMessage('지도를 불러올 수 없습니다. 모달을 닫고 브랜드 탭에서 지점을 선택해주세요.'); };
        const load = () => window.kakao?.maps ? window.kakao.maps.load(() => {
            if (!active || !container.current) return;
            map.current = new window.kakao.maps.Map(container.current, { center: new window.kakao.maps.LatLng(37.5665, 126.978), level: 7 });
            setReady(true);
        }) : fail();
        if (window.kakao?.maps) load();
        else if (key) {
            script = document.querySelector('script[data-smart-ticketing-kakao-map="true"]');
            const existing = Boolean(script);
            script ??= document.createElement('script');
            script.addEventListener('load', load); script.addEventListener('error', fail);
            if (!existing) {
                script.dataset.smartTicketingKakaoMap = 'true';
                script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false&libraries=services`;
                script.async = true; document.head.appendChild(script);
            }
        } else queueMicrotask(fail);
        return () => { active = false; requestSequence.value++; script?.removeEventListener('load', load); script?.removeEventListener('error', fail); map.current = null; };
    }, []);
    useEffect(() => {
        if (!ready || !map.current) return;
        const maps = window.kakao.maps;
        let active = true;
        const makeTooltip = below => {
            const content = document.createElement('div');
            content.className = `${styles.tooltip}${below ? ` ${styles.tooltipBelow}` : ''}`;
            content.setAttribute('role', 'tooltip');
            return new maps.CustomOverlay({ content, yAnchor: below ? 0 : 1, zIndex: 50 });
        };
        const tooltip = makeTooltip(false);
        const belowTooltip = makeTooltip(true);
        let hoveredMarker = null;
        const hideTooltip = () => { hoveredMarker = null; tooltip.setMap(null); belowTooltip.setMap(null); };
        const entries = theaters.filter(theater => theater.latitude != null && theater.longitude != null);
        const unique = [...new Map(entries.map(theater => [theater.id, theater])).values()];
        const markers = unique.map(theater => {
            const position = new maps.LatLng(Number(theater.latitude), Number(theater.longitude));
            const marker = new maps.Marker({
                map: map.current,
                position,
            });
            getTheaterMarkerImage(getTheaterBrand(theater), false, 42)
                .then(image => { if (active) marker.setImage(image); })
                .catch(() => { /* Keep the selectable fallback pin if a logo cannot load. */ });
            const click = () => onSelect(theater.id);
            const showTooltip = () => {
                if (!map.current || hoveredMarker === marker) return;
                hideTooltip();
                hoveredMarker = marker;
                const point = map.current.getProjection().containerPointFromCoords(position);
                const target = point.y < 65 ? belowTooltip : tooltip;
                target.getContent().textContent = theater.name;
                target.setPosition(position);
                target.setMap(map.current);
            };
            const leaveMarker = () => { if (hoveredMarker === marker) hideTooltip(); };
            const events = { click, mouseover: showTooltip, mouseout: leaveMarker };
            Object.entries(events).forEach(([event, handler]) => maps.event.addListener(marker, event, handler));
            return { marker, events };
        });
        return () => {
            active = false;
            hideTooltip();
            markers.forEach(({ marker, events }) => {
                Object.entries(events).forEach(([event, handler]) => maps.event.removeListener(marker, event, handler));
                marker.setMap(null);
            });
        };
    }, [theaters, ready, onSelect]);

    function moveToCurrentLocation() {
        if (!map.current || busy) return;
        if (!navigator.geolocation) {
            setMessage('이 브라우저는 위치 기능을 지원하지 않습니다. 지도를 직접 이동해주세요.');
            return;
        }
        const current = ++sequence.current.value;
        setBusy(true);
        setMessage('현재 위치를 확인하고 있습니다.');
        navigator.geolocation.getCurrentPosition(position => {
            if (sequence.current.value !== current || !map.current) return;
            map.current.setCenter(new window.kakao.maps.LatLng(position.coords.latitude, position.coords.longitude));
            setMessage('극장을 클릭해서 선택해주세요.');
            setBusy(false);
        }, error => {
            if (sequence.current.value !== current || !map.current) return;
            setMessage(error.code === 1
                ? '위치 권한이 허용되지 않았습니다. 브라우저에서 위치 권한을 허용한 뒤 다시 눌러주세요.'
                : '현재 위치를 확인하지 못했습니다. 잠시 후 다시 눌러주세요.');
            setBusy(false);
        }, { timeout: 10000, maximumAge: 0 });
    }
    return <section className={styles.content}>
        <div className={styles.toolbar}>
            <GlassButton disabled={!ready || busy} onClick={moveToCurrentLocation}>{busy ? '현재 위치 확인 중…' : '현재 위치로 이동'}</GlassButton>
            <p className={styles.message} role="status">{message}</p>
        </div>
        <div ref={container} className={styles.map} aria-label="극장 지도" />
    </section>;
}
