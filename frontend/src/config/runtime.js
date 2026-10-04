export function getKakaoMapKey() {
    return globalThis.window?.__SMART_TICKETING_CONFIG__?.kakaoMapJsKey
        || import.meta.env?.VITE_KAKAO_MAP_JS_KEY
        || '';
}
