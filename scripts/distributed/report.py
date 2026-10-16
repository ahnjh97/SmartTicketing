"""Print per-run k6 results; never mix warmup/smoke into the capacity comparison."""
import argparse
import json
from pathlib import Path


def render(directory, include_smoke=False):
    details = []
    rows = ['| 실행 | 시나리오 | 서버 | 흐름/s 설정 | 성공 HTTP/s | 완료 예매/s | 오류율 | 누락 | 기준 |',
            '|---|---|---:|---:|---:|---:|---:|---:|---|']
    for path in sorted(directory.glob('*.json')):
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            continue
        meta = data.get('metadata', {})
        if not meta or (meta.get('phase') != 'measurement'
                        and not (include_smoke and meta.get('phase') == 'smoke')):
            continue
        metrics = data.get('metrics', {})
        def value(name, key, default=0):
            return metrics.get(name, {}).get('values', {}).get(key, default)
        checks = [t['ok'] for metric in metrics.values() for t in metric.get('thresholds', {}).values()]
        state = 'PASS' if checks and all(checks) else 'FAIL/미확인'
        rows.append(f"| {path.stem} | {meta.get('suite')} | {meta.get('nodes', '?')} | {meta.get('rate')} | "
                    f"{value('successful_requests', 'rate'):.2f} | {value('completed_bookings', 'rate'):.2f} | "
                    f"{value('http_req_failed', 'rate'):.2%} | {value('dropped_iterations', 'count')} | {state} |")
        for name, metric in sorted(metrics.items()):
            if name.startswith('http_req_duration{name:'):
                details.append(f"- {path.stem} / {name}: p95 {metric['values'].get('p(95)', 0):.1f} ms")
    return '\n'.join(rows) + '\n\n' + '\n'.join(details)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path, default=Path('.local/distributed'))
    parser.add_argument('--include-smoke', action='store_true')
    args = parser.parse_args()
    print(render(args.directory, args.include_smoke))
