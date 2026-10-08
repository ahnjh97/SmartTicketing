import { Trend } from 'k6/metrics';
import { get, loadOptions, writeSummary } from './lib.js';

export const options = {
    ...loadOptions(),

    // 처음에는 너무 높은 부하를 주지 않고
    // 50 req/s로 동일하게 테스트
};

const mainDuration = new Trend('main_only_duration', true);

export default function () {
    get('/api/main', mainDuration);
}

export function handleSummary(data) {
    return writeSummary(data, '01-main-only', [
        'main_only_duration',
    ]);
}