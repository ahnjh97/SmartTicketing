import fs from 'node:fs';

const input = process.argv[2];
const output = process.argv[3];
const data = JSON.parse(fs.readFileSync(input, 'utf8'));
const esc = (v) => String(v ?? '—').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const summarize = (b) => {
  const r = b && b.results;
  const rows = Array.isArray(r && r.rows) ? r.rows : [];
  const validations = Array.isArray(r && r.validation) ? r.validation : [];
  const status = b && b.status === 'completed' ? '완료' : '실패 또는 미완료';
  const heading = '<h2>' + esc(b && b.label) + '</h2><p><strong>' + status + '</strong></p>' +
    '<p>브랜치: <code>' + esc(b && b.ref) + '</code></p><p>커밋: <code>' + esc(b && b.commit) + '</code></p>' +
    '<p>종료 코드: ' + esc(b && b.exitCode) + '</p><p>원본 산출물: <code>' + esc(b && b.artifacts) + '</code></p>';
  const validationHtml = validations.length ? '<ul>' + validations.map(v => '<li>실행 ' + esc(v.run) + ': 배정 ' + esc(v.assigned) + ' / 테스트 케이스 ' + esc(v.dispatchCases) + ' / 중복 활성 좌석 ' + esc(v.duplicateActiveSeats) + '</li>').join('') + '</ul>' : '<p>구조화된 정합성 검증 결과 없음</p>';
  const metricRows = rows.map((row,i) => {
    const values = [
      row.p50 ?? row.metrics?.http_req_duration?.values?.med ?? null,
      row.p95 ?? row.metrics?.http_req_duration?.values?.['p(95)'] ?? null,
      row.p99 ?? row.metrics?.http_req_duration?.values?.['p(99)'] ?? null,
      row.rps ?? row.metrics?.http_reqs?.values?.rate ?? null,
      row.httpFailed ?? row.metrics?.http_req_failed?.values?.fails ?? null,
      row.failed ?? null
    ];
    return '<tr><td>' + esc(row.scenario ?? row.name ?? ('시나리오 ' + (i+1))) + '</td>' + values.map(v => '<td>' + (typeof v === 'number' ? esc(Number(v.toFixed(2))) : '측정 안 됨') + '</td>').join('') + '</tr>';
  }).join('');
  return {html:'<section class="card">' + heading + '</section><section><h3>' + esc(b && b.label) + ' 지표</h3><div class="scroll"><table><thead><tr><th>시나리오</th><th>p50 (ms)</th><th>p95 (ms)</th><th>p99 (ms)</th><th>요청/초</th><th>HTTP 실패</th><th>기능 실패</th></tr></thead><tbody>' + (metricRows || '<tr><td colspan="7">결과 행 없음 — 성능 비교 불가</td></tr>') + '</tbody></table></div><h3>정합성 확인</h3>' + validationHtml + '</section>', rows};
};
const baseline = summarize(data.baseline);
const improved = summarize(data.improved);
const html = '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>대기순위 Redis 브랜치 비교</title>' +
'<style>body{font:15px/1.6 system-ui,sans-serif;max-width:1250px;margin:30px auto;padding:0 18px;color:#202a35;background:#f5f7fa}h1{font-size:28px}.card,section{background:#fff;border:1px solid #d8dee7;border-radius:10px;padding:16px;margin:16px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}.scroll{overflow:auto}table{border-collapse:collapse;width:100%;white-space:nowrap}th,td{border:1px solid #d8dee7;padding:9px;text-align:right}th:first-child,td:first-child{text-align:left}thead{background:#eef2f7}.warn{background:#fff5d6;border-left:4px solid #b88700;padding:12px 16px}code{overflow-wrap:anywhere}.muted{color:#596579}</style></head><body>' +
'<h1>대기순위 Redis 개선 전후 비교</h1><p>비교 ID: ' + esc(data.comparisonId) + ' · 생성: ' + esc(data.generatedAt) + '</p>' +
'<div class="warn">각 브랜치는 별도 Git worktree에서 실행했습니다. 실행 실패나 누락된 지표를 성능 향상으로 간주하지 않습니다. 현재 벤치마크 산출물에 실제 포함된 값만 표시하며, 없는 값은 측정 안 됨으로 남깁니다.</div>' +
'<div class="grid">' + baseline.html + improved.html + '</div>' +
'<section><h2>측정 한계</h2><ul><li>기존 k6 booking 시나리오의 결과를 비교합니다. 동일 부하 프로필과 격리 컨테이너를 사용합니다.</li><li>DB COUNT 쿼리 수, 등록/변경 API 지연, Outbox→Redis 전파 지연은 산출물에 계측값이 있을 때만 표시됩니다. 값이 없으면 이 보고서만으로 해당 항목의 개선을 주장할 수 없습니다.</li><li>두 브랜치 중 하나라도 실패했다면 성능 개선 여부는 확정할 수 없습니다.</li><li>기존 benchmark-results/index.html은 이 스크립트에서 덮어쓰지 않습니다.</li></ul><p class="muted">원본 비교 데이터: ' + esc(data.comparisonId) + '/comparison.json</p></section></body></html>';
fs.writeFileSync(output, html, 'utf8');
console.log('Generated waiting-rank comparison report: ' + output);
