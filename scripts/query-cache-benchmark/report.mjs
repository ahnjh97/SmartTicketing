import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';

const beforePath = resolve(process.argv[2] || '.local/query-cache-benchmark/before.json');
const afterPath = resolve(process.argv[3] || '.local/query-cache-benchmark/after.json');
const outPath = resolve(process.argv[4] || '.local/query-cache-benchmark/report.html');
const [before, after] = await Promise.all([
  readFile(beforePath, 'utf8').then(JSON.parse),
  readFile(afterPath, 'utf8').then(JSON.parse),
]);

const esc = value => String(value ?? '—').replace(/[&<>"']/g, c => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));
const metric = (run, name, field) => run.metrics?.[name]?.values?.[field];
const fmtMs = n => Number.isFinite(n) ? n.toLocaleString('ko-KR', { maximumFractionDigits: 2 }) + ' ms' : '—';
const fmtNum = n => Number.isFinite(n) ? n.toLocaleString('ko-KR', { maximumFractionDigits: 2 }) : '—';
const fmtPct = n => Number.isFinite(n) ? (n * 100).toFixed(2) + '%' : '—';
const rows = [
  ['HTTP 요청 수', 'http_reqs', 'count', fmtNum],
  ['요청 처리량', 'http_reqs', 'rate', n => fmtNum(n) + ' req/s'],
  ['평균 응답 시간', 'http_req_duration', 'avg', fmtMs],
  ['중앙값 p50', 'http_req_duration', 'med', fmtMs],
  ['p90 응답 시간', 'http_req_duration', 'p(90)', fmtMs],
  ['p95 응답 시간', 'http_req_duration', 'p(95)', fmtMs],
  ['p99 응답 시간', 'http_req_duration', 'p(99)', fmtMs],
  ['최대 응답 시간', 'http_req_duration', 'max', fmtMs],
  ['HTTP 실패율', 'http_req_failed', 'rate', fmtPct],
  ['검증 통과율', 'checks', 'rate', fmtPct],
];
const value = (run, row) => row[3](metric(run, row[1], row[2]));
const beforeP95 = metric(before, 'http_req_duration', 'p(95)');
const afterP95 = metric(after, 'http_req_duration', 'p(95)');
const p95Change = Number.isFinite(beforeP95) && beforeP95 > 0 && Number.isFinite(afterP95)
  ? (beforeP95 - afterP95) / beforeP95 * 100 : null;
const beforeAvg = metric(before, 'http_req_duration', 'avg');
const afterAvg = metric(after, 'http_req_duration', 'avg');
const avgChange = Number.isFinite(beforeAvg) && beforeAvg > 0 && Number.isFinite(afterAvg)
  ? (beforeAvg - afterAvg) / beforeAvg * 100 : null;
const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Redis 읽기 캐시 A/B 비교</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#f5f7fb;color:#172033;margin:0;padding:30px}
main{max-width:1000px;margin:auto}h1{font-size:27px}p{line-height:1.6;color:#526078}
.cards{display:grid;grid-template-columns:1fr 1fr;gap:14px}.card{background:white;border:1px solid #dfe5ef;border-radius:12px;padding:18px}
table{width:100%;border-collapse:collapse;background:white;margin-top:22px}th,td{text-align:left;padding:12px;border-bottom:1px solid #e6eaf1}th{background:#edf1f7}
.callout{padding:14px;background:#fff4db;border-radius:10px;color:#694b12}.big{font-size:20px;font-weight:700}
@media(max-width:680px){body{padding:14px}.cards{grid-template-columns:1fr}th,td{padding:8px;font-size:13px}}
</style></head><body><main>
<h1>Redis 읽기 쿼리 캐시 A/B 비교</h1>
<p>Before: ${esc(before.cacheMode)} · After: ${esc(after.cacheMode)} · VU: ${esc(before.vus)} / ${esc(after.vus)} · Duration: ${esc(before.duration)} / ${esc(after.duration)}</p>
<div class="callout">읽기 캐시를 끈 상태와 켠 상태를 비교합니다. 한 번의 실행만으로 결론 내리지 말고 여러 번 반복하세요. 이 결과는 쓰기/예매 동시성 성능을 나타내지 않습니다.</div>
<div class="cards">
<section class="card"><h2>적용 전</h2><p>label: ${esc(before.label)}</p><p>요청 처리량: <b>${esc(fmtNum(metric(before,'http_reqs','rate')))} req/s</b></p><p>평균: <b>${esc(fmtMs(beforeAvg))}</b></p><p>p95: <b>${esc(fmtMs(beforeP95))}</b></p></section>
<section class="card"><h2>적용 후</h2><p>label: ${esc(after.label)}</p><p>요청 처리량: <b>${esc(fmtNum(metric(after,'http_reqs','rate')))} req/s</b></p><p>평균: <b>${esc(fmtMs(afterAvg))}</b></p><p>p95: <b>${esc(fmtMs(afterP95))}</b></p></section>
</div>
<h2>지표 상세</h2><table><thead><tr><th>지표</th><th>적용 전</th><th>적용 후</th></tr></thead><tbody>
${rows.map(row => `<tr><td>${esc(row[0])}</td><td>${esc(value(before,row))}</td><td>${esc(value(after,row))}</td></tr>`).join('')}
</tbody></table>
<h2>p95 변화</h2><p class="big">${p95Change === null ? '계산 불가' : (p95Change >= 0 ? `약 ${fmtNum(p95Change)}% 단축` : `약 ${fmtNum(Math.abs(p95Change))}% 증가`)}</p>
<h2>평균 응답 시간 변화</h2><p class="big">${avgChange === null ? '계산 불가' : (avgChange >= 0 ? `약 ${fmtNum(avgChange)}% 단축` : `약 ${fmtNum(Math.abs(avgChange))}% 증가`)}</p>
</main></body></html>`;
await mkdir(dirname(outPath), { recursive: true });
await writeFile(outPath, html, 'utf8');
console.log('HTML report written: ' + outPath);
