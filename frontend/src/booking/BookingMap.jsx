import GlassButton from '../components/GlassButton.jsx';
import styles from './BookingMap.module.css';
import ui from './BookingComponents.module.css';
import { useEffect, useRef, useState } from 'react';
import { theaterApi } from '../api/theaters.js';
import useAuth from '../hooks/useAuth.js';
import { getKakaoMapKey } from '../config/runtime.js';

// Reuse the SDK key/script identity and authenticated nearby API of ResidencePreference.
export default function BookingMap({ theaters, onSelect }) {
    const { user } = useAuth();
    const container = useRef(null);
    const map = useRef(null);
    const [ready, setReady] = useState(false);
    const [center, setCenter] = useState(null);
    const [message, setMessage] = useState('위치 권한을 요청합니다. 지도에서 극장 핀을 선택할 수 있습니다.');
    const [nearby, setNearby] = useState([]);
    const [busy, setBusy] = useState(false);
    const sequence = useRef({ value: 0 });
    useEffect(() => {
        let active = true;
        const requestSequence = sequence.current;
        navigator.geolocation?.getCurrentPosition(position => {
            if (active) { setCenter({ latitude: position.coords.latitude, longitude: position.coords.longitude }); setMessage('현재 위치를 표시했습니다. 지도에서 극장 핀을 선택해주세요.'); }
        }, () => { if (active) setMessage('위치 정보를 사용할 수 없습니다. 지도를 움직여 극장 핀을 선택해주세요.'); }, { timeout: 10000 });
        if (!navigator.geolocation) queueMicrotask(() => { if (active) setMessage('위치 기능을 지원하지 않습니다. 지도에서 극장 핀을 선택해주세요.'); });
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
        if (ready && center) map.current?.setCenter(new window.kakao.maps.LatLng(center.latitude, center.longitude));
    }, [ready, center]);
    useEffect(() => {
        if (!ready || !map.current) return;
        const maps = window.kakao.maps;
        const entries = [...theaters, ...(user ? nearby : [])].filter(t => t.latitude != null && t.longitude != null);
        const unique = [...new Map(entries.map(t => [t.id, t])).values()];
        const markers = unique.map(t => {
            const marker = new maps.Marker({ map: map.current, position: new maps.LatLng(t.latitude, t.longitude), title: t.name });
            const click = () => onSelect(t.id);
            maps.event.addListener(marker, 'click', click);
            return { marker, click };
        });
        return () => markers.forEach(({ marker, click }) => { maps.event.removeListener(marker, 'click', click); marker.setMap(null); });
    }, [theaters, nearby, ready, onSelect, user]);
    async function searchNearby() {
        if (!user || !map.current) return;
        const current = ++sequence.current.value;
        const point = map.current.getCenter();
        setBusy(true);
        try {
            const result = await theaterApi.nearby({ latitude: point.getLat(), longitude: point.getLng() });
            if (sequence.current.value !== current) return;
            setNearby(result.map(t => ({ ...t, id: t.theaterId })));
            setMessage(result.length ? '주변 극장을 선택해주세요.' : '주변 극장이 없습니다. 지도를 다른 위치로 옮겨 다시 찾아주세요.');
        } catch (error) { if (sequence.current.value === current) setMessage(error.message); }
        finally { if (sequence.current.value === current) setBusy(false); }
    }
    return <section className={ui.panel}><p role="status">{message}</p>
        <div ref={container} className={styles.map} aria-label="극장 지도" />
        {user ? <GlassButton disabled={!ready || busy} onClick={searchNearby}>{busy ? '검색 중…' : '이 지도 위치 주변 검색'}</GlassButton> : <p>주변 극장 검색은 로그인이 필요합니다. 지도에 표시된 극장 핀은 비회원도 선택할 수 있습니다.</p>}
        {user && nearby.map(t => <GlassButton key={t.id} onClick={() => onSelect(t.id)}>{t.name}</GlassButton>)}
    </section>;
}
