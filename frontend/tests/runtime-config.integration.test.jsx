import { afterEach, describe, expect, it, vi } from 'vitest';
import { getKakaoMapKey } from '../src/config/runtime.js';

afterEach(() => {
    delete window.__SMART_TICKETING_CONFIG__;
    vi.unstubAllEnvs();
});

describe('Kakao runtime and build configuration', () => {
    it('uses the container runtime key ahead of a build-time value', () => {
        vi.stubEnv('VITE_KAKAO_MAP_JS_KEY', 'pages-key');
        window.__SMART_TICKETING_CONFIG__ = { kakaoMapJsKey: 'aws-key' };
        expect(getKakaoMapKey()).toBe('aws-key');
    });

    it('preserves build-time configuration for Pages and local development', () => {
        vi.stubEnv('VITE_KAKAO_MAP_JS_KEY', 'pages-key');
        window.__SMART_TICKETING_CONFIG__ = {};
        expect(getKakaoMapKey()).toBe('pages-key');
    });

    it('returns an empty key when neither configuration is present', () => {
        vi.stubEnv('VITE_KAKAO_MAP_JS_KEY', '');
        expect(getKakaoMapKey()).toBe('');
    });
});
