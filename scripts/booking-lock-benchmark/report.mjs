import { readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';

const beforePath = resolve(process.argv[2] || '.local/booking-lock-benchmark/before.json');
const afterPath = resolve(process.argv[3] || '.local/booking-lock-benchmark/after.json');
const outPath = resolve(process.argv[4] || '.local/booking-lock-benchmark/report.html');
const before = JSON.parse(await readFile(beforePath, 'utf8'));
const after = JSON.parse(await readFile(afterPath, 'utf8'));
const esc = v => String(v ?? '—').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const metric = (run, name, field) => run.metrics?.[name]?.values?.[field];
const ms = n => Number.isFinite(n) ? n.toLocaleString('ko-KR',{maximumFractionDigits:2})+' ms' : '—';
const num = n => Number.isFinite(n) ? n.toLocaleString('ko-KR',{maximumFractionDigits:2}) : '—';
const rows = [
  ['요청 수','http_reqs','count','number'],
  ['평균 응답시간','http_req_duration','avg','ms'],
  ['중앙값 p50','http_req_duration','med','ms'],
  ['p90','http_req_duration','p(90)','ms'],
  ['p95','http_req_duration','p(95)','ms'],
  ['p99','http_req_duration','p(99)','ms'],
  ['최대 응답시간','http_req_duration','max','ms'],
  ['HTTP 실패율','http_req_failed','rate','percent'],
  ['스마트 선점 성공','smart_hold_success','count','number'],
  ['좌석/상태 충돌(409)','smart_hold_conflict','count','number'],
  ['기타 HTTP 응답','smart_hold_other_http','count','number'],
  ['네트워크 오류','smart_hold_transport_error','count','number'],
];
const value = (run, row) => {
  const n=metric(run,row[1],row[2]);
  if(!Number.isFinite(n))return '—';
  return row[3]==='ms'?ms(n):row[3]==='percent'?(n*100).toFixed(2)+'%':num(n);
};
const b95=metric(before,'http_req_duration','p(95)'), a95=metric(after,'http_req_duration','p(95)');
const delta=Number.isFinite(b95)&&b95>0&&Number.isFinite(a95)?((b95-a95)/b95*100):null;
const status = run => [
  ['성공',metric(run,'smart_hold_success','count')||0],
  ['409 충돌',metric(run,'smart_hold_conflict','count')||0],
  ['기타 HTTP',metric(run,'smart_hold_other_http','count')||0],
  ['네트워크 오류',metric(run,'smart_hold_transport_error','count')||0],
].map(([k,v])=>`<span><b>${esc(k)}</b> ${num(v)}</span>`).join('');
const html = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PESSIMISTIC_WRITE 비교 보고서</title>
<style>body{font-family:system-ui,-apple-system,sans-serif;background:#f5f7fb;color:#172033;margin:0;padding:32px}main{max-width:1100px;margin:auto}h1{font-size:28px}p{line-height:1.65;color:#526078}.cards{display:grid;grid-template-columns:1fr 1fr;gap:16px}.card{background:#fff;border:1px solid #dfe5ef;border-radius:14px;padding:20px}.metrics{display:flex;gap:16px;flex-wrap:wrap}.metrics span{background:#f1f4f9;padding:8px 10px;border-radius:8px}table{width:100%;border-collapse:collapse;background:#fff;margin-top:22px}th,td{text-align:left;padding:12px;border-bottom:1px solid #e6eaf1}th{background:#edf1f7}.delta{font-size:20px;font-weight:700;color:#172033}.warn{padding:14px;border-radius:10px;background:#fff4db;color:#694b12}@media(max-width:700px){body{padding:14px}.cards{grid-template-columns:1fr}th,td{padding:8px;font-size:13px}}</style></head><body><main>
<h1>스마트 예매 좌석 재고 잠금 성능 비교</h1>
<p>회차 #${esc(before.showtimeId)} · 영화 #${esc(before.movieId)} · 관람일 ${esc(before.viewingDate)} · 인원 ${esc(before.partySize)}명 · 동시 요청 ${esc(before.vus)}개</p>
<div class="warn">전제: 두 실행은 동일한 서버 빌드/DB 데이터/회차를 사용하고, 서버 재시작 후 각 잠금 설정만 바꿔 측정해야 합니다. 이 보고서는 결과를 비교하지만 환경이 동일했는지는 자동으로 보증하지 않습니다. 성공/409 비율은 좌석 경쟁 결과이므로 응답시간과 별도로 해석하세요.</div>
<div class="cards"><section class="card"><h2>적용 전</h2><p>설정: ${esc(before.lockMode)} · 시작: ${esc(before.startedAt)}</p><div class="metrics">${status(before)}</div></section>
<section class="card"><h2>적용 후</h2><p>설정: ${esc(after.lockMode)} · 시작: ${esc(after.startedAt)}</p><div class="metrics">${status(after)}</div></section></div>
<h2>지표 비교</h2><table><thead><tr><th>지표</th><th>적용 전</th><th>적용 후</th></tr></thead><tbody>${rows.map(r=>`<tr><td>${esc(r[0])}</td><td>${esc(value(before,r))}</td><td>${esc(value(after,r))}</td></tr>`).join('')}</tbody></table>
<h2>p95 응답시간 변화</h2><p class="delta">${delta===null?'계산할 수 없음':(delta>=0?'약 '+num(delta)+'% 단축':'약 '+num(Math.abs(delta))+'% 증가')}</p>
<p>HTTP 2xx 성공 건수와 409 충돌 건수를 함께 확인하세요. 테스트 결과가 성공률 저하, 중복 좌석 선점 또는 DB 교착을 동반하면 응답시간이 짧아도 개선으로 판단하면 안 됩니다.</p>
</main></body></html>`;
await (await import('node:fs/promises')).mkdir(dirname(outPath),{recursive:true});
await writeFile(outPath, html, 'utf8');
console.log('HTML report written: '+outPath);
