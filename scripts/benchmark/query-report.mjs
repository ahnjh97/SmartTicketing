import {readFile,writeFile,mkdir,copyFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
const source=resolve(process.argv[2]),target=resolve(process.argv[3]);
const read=async p=>JSON.parse(await readFile(p,'utf8'));
await mkdir(target,{recursive:true});
const env=await read(join(source,'environment.json'));
const compare=(env.cacheMode??'compare')==='compare';
const executions=await read(join(source,'executions.json'));
const rows=[];
function cleanSummary(data) {
    const m=data.metrics;
    const req=m.httpReqs??m.http_reqs?.values??m.http_reqs;
    const errors=m.httpReqFailed??m.http_req_failed?.values??m.http_req_failed;
    const checks=m.checks?.values??m.checks;
    const drops=m.droppedIterations??m.dropped_iterations?.values??m.dropped_iterations;
    return req?.count>0 && (errors?.rate??errors?.value)===0 && checks?.fails===0 && (drops?.count??0)===0;
}
for(const execution of executions) {
    const data=await read(join(source,execution.file));
    const m=data.metrics;
    const duration=m.httpReqDuration??m.http_req_duration?.values??m.http_req_duration;
    const failed=m.httpReqFailed??m.http_req_failed?.values??m.http_req_failed;
    const requests=m.httpReqs??m.http_reqs?.values??m.http_reqs;
    const checks=m.checks?.values??m.checks;
    const dropped=m.droppedIterations??m.dropped_iterations?.values??m.dropped_iterations;
    const count=requests?.count??0,dropCount=dropped?.count??0;
    const errorRate=failed?.rate??failed?.value??null;
    const checkFails=checks?.fails??0;
    const warmup=await read(join(source,execution.warmupFile));
    const warmupValid=cleanSummary(warmup) && execution.warmupExitCode===0;
    const valid=cleanSummary(data) && Number.isFinite(duration?.['p(95)']) && execution.exitCode===0 && warmupValid;
    rows.push({...execution,p95:duration?.['p(95)']??null,p99:duration?.['p(99)']??null,
        requests:count,rps:requests?.rate??null,errorRate,checkFails,dropped:dropCount,valid,warmupValid,load:data.load??null,warmupLoad:warmup.load??null});
    const destination=join(target,'raw',execution.run);await mkdir(destination,{recursive:true});
    await copyFile(join(source,execution.file),join(target,'raw',execution.file));
    await copyFile(join(source,execution.warmupFile),join(target,'raw',execution.warmupFile));
}
const pairs=[...new Set(rows.map(r=>r.id))].map(id=>{
    const subset=rows.filter(r=>r.id===id),off=subset.filter(r=>r.mode==='OFF'),on=subset.filter(r=>r.mode==='ON');
    const median=a=>{if(!a.length)return null;const s=[...a].sort((a,b)=>a-b);return (s[Math.floor((s.length-1)/2)]+s[Math.floor(s.length/2)])/2;};
    const baseline=median(off.map(r=>r.p95)),cached=median(on.map(r=>r.p95));
    const sameLoad=new Set(subset.map(r=>JSON.stringify(r.load))).size===1;
    const sameLocalCache=subset.every(r=>r.cache?.local===true&&r.cache.localTtlMs===500&&r.cache.redisTtlMs===2000&&r.cache.redis===(r.mode==='ON'));
    const seconds=value=>{const m=/^(\d+)(s|m)$/.exec(value??'');return m?Number(m[1])*(m[2]==='m'?60:1):0;};
    const valid=(env.smoke?off.length===1:[2,4].includes(off.length))&&off.length===on.length&&subset.every(r=>r.valid)&&sameLoad&&sameLocalCache;
    const performanceEligible=!env.smoke&&valid&&off.length===4&&subset.every(r=>r.p95>0&&r.requests>=200&&seconds(r.load?.duration)>=60&&seconds(r.warmupLoad?.duration)>=30);
    const changes=performanceEligible?off.map((r,i)=>(on[i].p95/r.p95-1)*100):[];
    return {id,name:subset[0].name,off:baseline,on:cached,valid,
        performanceEligible,
        pairedP95ChangesPercent:changes,change:median(changes),
        observation:changes.length?(Math.min(...changes)<=0&&Math.max(...changes)>=0?'반복마다 방향 다름 — 결론 보류':'같은 방향 관측 — 통계적 유의성 보장 아님'):null};
});
const isolated=rows.every(r=>r.pid&&r.database)&&new Set(rows.map(r=>r.containerId??r.pid)).size===rows.length&&new Set(rows.map(r=>r.database)).size===rows.length;
const complete=rows.length===env.plannedExecutions&&isolated;
await writeFile(join(target,'results.json'),JSON.stringify({env,rows,pairs,complete},null,2));
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=n=>n==null?'—':Number(n).toLocaleString('ko-KR',{maximumFractionDigits:2});
const failures=rows.filter(r=>!r.valid).length;
const html=`<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Linux k6 조회·로그인 결과</title>
<style>body{font:16px/1.6 system-ui,sans-serif;background:#0b1220;color:#e8edf5;margin:0}main{max-width:1250px;margin:auto;padding:32px}a{color:#66dac2}table{border-collapse:collapse;width:100%;white-space:nowrap}td,th{padding:12px;text-align:right;border-bottom:1px solid #334155}td:first-child,th:first-child{text-align:left}.scroll{overflow:auto}.note{background:#192538;padding:18px;margin:20px 0}.bad{color:#ffb68b}.good{color:#66dac2}code{overflow-wrap:anywhere}</style>
<main><h1>Linux k6 조회·로그인 결과 ${env.smoke?'(빠른 동작 확인)':''}</h1>
<p>${esc(env.startedAt)} · ${esc(env.os)} · Java ${esc(env.java)} · ${esc(env.suite)} · Redis ${compare?'ON/OFF 비교':esc(env.cacheMode.toUpperCase())+' 단독'}</p>
<div class="note"><strong>${complete?'계획된 실행 완료':'실행 미완료'} · ${rows.length}/${env.plannedExecutions}개 · 실패/부하 미달 구간 ${failures}개</strong>
<p>영화 200개·극장 20개·회차 20개·좌석 2,000개의 고정된 합성 데이터. 개발 DB와 분리한 임시 환경입니다. 실제 운영 데이터 규모를 재현하지 않았습니다.</p>
<p>Redis 공통 스위치만 바꿉니다. 로컬 캐시(500ms)와 동시 요청 병합은 양쪽에 동일하게 유지하며 Redis 조회 TTL도 2초로 고정합니다. 로그인은 임시 계정 하나로 실행하며 ON/OFF 비교에서 제외합니다.</p>
<p>항목·모드마다 새 JVM·새 DB·새 Redis 키 범위로 시작합니다. ${compare?'동일 항목을 OFF → ON → ON → OFF 순서로 두 번 반복합니다(이전 결과는 한 번, 스모크는 OFF → ON).':'선택한 모드로 한 번 측정하며, ON/OFF 개선율을 계산하지 않습니다.'} 예열을 포함한 메타데이터는 JSON에 기록합니다.</p>
<p>${env.smoke?'Smoke는 모드당 한 번, 3초 예열 후 짧게 동작을 확인하며 성능 결론을 내리지 않습니다.':'조회는 동일 요청률로 예열하고 본 측정에서 제외합니다. 현재 기본값은 예열 30초·모드별 4회입니다. 표의 p95는 실행별 p95의 중앙값이며 변화는 짝지은 반복별 값입니다. 이전 15초/2회 결과는 진단용으로 남깁니다.'}
요청 실패·검사 실패·누락된 반복(dropped iterations)이 있으면 성능 비교를 보류합니다.</p>
<p>WSL2 Docker에서 ${env.fixture?.execution==='production-prebuilt-jar'?'배포용 prebuilt 이미지의 app.jar를 실행합니다. k6는 별도 컨테이너에서 실행하며 Redis AOF를 켭니다.':'앱과 k6는 컨테이너를 공유합니다.'} 실행 당시 자원 설정은 원본 실행 기록을 참고하세요. DB 버퍼 풀·OS 캐시는 강제 비우지 않으며, 동일한 예열 후의 성능을 비교합니다. 콜드 스타트나 AWS 최대 처리량을 뜻하지 않습니다. <a href="runner.log">실행 로그</a></p></div>
<h2>ON/OFF 비교</h2><div class="scroll"><table><thead><tr><th>항목</th><th>OFF p95 ms</th><th>ON p95 ms</th><th>변화</th></tr></thead><tbody>
${pairs.map(p=>`<tr><td>${esc(p.name)}</td><td>${fmt(p.off)}</td><td>${fmt(p.on)}</td><td>${p.id==='login'?'비교 대상 아님':!compare?'단독 측정':env.smoke?'기능 확인 전용':!p.performanceEligible||!complete?'비교 보류 — 반복/표본 부족 또는 실행 실패':p.pairedP95ChangesPercent.map(n=>fmt(n)+'%').join(', ')+' · '+esc(p.observation)}</td></tr>`).join('')}
</tbody></table></div><h2>실행별 결과</h2><div class="scroll"><table><thead><tr><th>실행 / 항목</th><th>p95 ms</th><th>p99 ms</th><th>요청</th><th>HTTP/s</th><th>HTTP 실패 %</th><th>검사 실패</th><th>누락 반복</th><th>k6 종료</th><th>원본</th></tr></thead><tbody>
${rows.map(r=>`<tr><td>${esc(r.run+' / '+r.id)}${r.warmupValid?'':' (예열 실패)'}</td><td>${fmt(r.p95)}</td><td>${fmt(r.p99)}</td><td>${fmt(r.requests)}</td><td>${fmt(r.rps)}</td><td>${fmt(r.errorRate==null?null:r.errorRate*100)}</td><td>${r.checkFails}</td><td>${r.dropped}</td><td>${r.exitCode}</td><td><a href="raw/${esc(r.file)}">JSON</a> · <a href="raw/${esc(r.warmupFile)}">예열</a></td></tr>`).join('')}
</tbody></table></div><p><a href="results.json">가공 결과 JSON (부하 설정 포함)</a></p></main></html>`;
await writeFile(join(target,'index.html'),html);
console.log(`Report: ${target}/index.html; completed=${complete}; failed/degraded=${failures}`);
if(!complete||failures||(compare&&pairs.some(p=>p.id!=='login'&&!p.valid))) process.exitCode=2;
