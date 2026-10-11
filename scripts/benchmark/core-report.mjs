import {readFile,writeFile,readdir,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
const specs = [
  ['waiting','대기순번 조회','앞 대기자 DB 조회 → 버전 검증 + Redis Sorted Set','booking'],
  ['seats','좌석도 조회','매번 좌석 조회 → TTL 2초 동안 응답 재사용','seats'],
  ['showtimes','상영시간표 조회','좌석 엔티티 조회 → 필요한 필드 조회 + 결과 캐싱','showtimes'],
  ['dispatch','취소 후 대기자 자동 선점','조회 쓰기 잠금·즉시 호출 → 읽기 분리·구역 잠금·Outbox','booking'],
];
export const metricSpecs = {
  waiting: {question:'같은 순번을 반환하면서 DB의 앞 대기자 조회를 줄였는가?', metrics:[['rankSqlPerOp','순번 계산 SQL / 요청','회'],['rowsPerOp','DB 결과 행 / 요청','행']], note:'순번 SQL은 waiting_queues의 COALESCE 순번 비교 쿼리만 센다. 결과 행은 요청 전체에서 소비한 JDBC 행이며 DB 내부 스캔 행 수가 아니다.'},
  seats: {question:'반복 좌석도 조회가 DB에 도달하는 횟수를 줄였는가?', metrics:[['sqlPerOp','HTTP 요청당 SQL','회']], note:'캐시 적중·미스를 모두 포함한 JDBC 실행 횟수. 별도 스레드의 Outbox·만료 워커 SQL은 제외하며, 실제 120석 배치와 가용 상태를 검사한다.'},
  showtimes: {question:'20회차 × 120석을 반복해서 읽는 비용을 줄였는가?', metrics:[['inventoryRowsPerOp','좌석 재고 결과 행 / 요청','행']], note:'showtime_seats를 포함하는 SQL에서 소비한 결과 행만 센다. 네트워크 바이트·메모리·DB 스캔량은 아니다. 캐시 앞의 영화 검증 SQL은 이 지표에 포함되지 않는다.'},
  dispatch: {question:'동시 요청이 실패 없이 정확한 첫 대기자에게 좌석을 넘기는가?', metrics:[['success','전체 흐름 성공률','%'],['duplicateActive','중복 활성 선점','건']], note:'순번 확인 → 취소 → HOLDING → 정확한 좌석 확인까지 모두 성공해야 1건이다. HTTP 오류·데드락도 실패로 포함한다. 서로 다른 회차의 동시 흐름이며 동일 좌석에 대한 다중 명령 테스트는 아니다. DB 조회·잠금·Outbox의 종합 비교이며 장애 복구나 exactly-once 보장은 검증하지 않는다.'},
};
const changes = {
  waiting: {
    before:'MySQL에서 앞 대기자 목록을 잠그고 조회해 순번을 계산.',
    after:'Redis Sorted Set에서 순번 조회. DB 버전과 다르면 MySQL COUNT로 보완.',
    interpretation:'순번 SQL이 줄면 앞 대기자 목록을 읽는 경로를 우회한 것이다. 전체 DB 결과 행도 함께 보되, 인증·그룹·버전 검증까지 DB 접근이 모두 사라진다는 뜻은 아니다.',
  },
  seats: {
    before:'요청마다 MySQL에서 회차 정보와 좌석 120개를 조회.',
    after:'Redis에 좌석도 응답을 2초간 캐싱해 반복 DB 조회를 줄임.',
    interpretation:'캐시 적중과 갱신을 모두 포함해 SQL 총횟수를 실제 요청 수로 나눈다. 전후 요청 수가 조금 달라도 이 비율로 비교한다. 이 결과에서 정확한 캐시 적중률을 역산하지 않는다.',
  },
  showtimes: {
    before:'요청마다 MySQL에서 20회차 × 120석을 읽어 가용 좌석을 계산.',
    after:'필요한 재고 필드만 조회하고, 계산한 시간표를 Redis TTL 캐시로 재사용.',
    interpretation:'필드 축소는 한 행의 크기를 줄이는 변경이다. 여기서 측정하는 행 수 감소는 반복 재고 조회를 생략한 효과이며, 필드 축소의 바이트·메모리 절감은 측정하지 않았다.',
  },
  dispatch: {
    before:'순번 조회에도 MySQL 쓰기 잠금을 사용하고, 취소 시 회차 전체 재고를 잠금.',
    after:'조회 잠금 제거 · 구역과 대상 좌석으로 잠금 범위 축소 · 잠금 순서 정리 · Outbox로 후속 배정.',
    interpretation:'읽기 분리와 잠금 범위·순서 변경은 경합을 줄이려는 개선이다. Outbox는 후속 작업을 DB에 남기는 구조적 개선이다. 이 실험은 둘을 합친 흐름을 비교하므로 Redis나 Outbox 하나가 데드락을 해결했다고 단정하지 않는다.',
  },
};
const nonnegative = v => Number.isFinite(v) && v >= 0;
// Count the Hibernate warning once, not every repeated exception/stack-trace mention.
export function deadlockEvidence(log) {
  return {warnings:log.split(/\r?\n/).filter(line=>/\bWARN\b.*Deadlock found when trying to get lock/.test(line)).length,
    waitingRead:/BookingWaitingService\.get\(/.test(log)&&/coalesce\(wq1_0\.zone_queue_number,wq1_0\.queue_number\).*for update of wq1_0/.test(log)};
}
const total = (runs,key) => runs.length&&runs.every(r=>nonnegative(r[key]))?runs.reduce((sum,r)=>sum+r[key],0):null;
export function requestMetrics(d) {
  const m=d?.metrics??{}, operations=(m.operation_success?.values?.passes??0)+(m.operation_success?.values?.fails??0);
  const value=k=>m[k]?.values?.count;
  const result={sql:value('request_sql'),rows:value('request_rows'),rankSql:value('rank_sql'),inventoryRows:value('inventory_rows')};
  return {...result,valid:operations>0&&m.jdbc_instrumented?.values?.rate===1&&value('jdbc_samples')===operations&&Object.values(result).every(nonnegative)};
}
export function reduction(item,key) {
  const [before,after]=item.modes.map(m=>m[key]);
  return item.valid&&before>0&&nonnegative(after)?(1-after/before)*100:null;
}
const esc = s => String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt = v => Number.isFinite(v)?v.toLocaleString('ko-KR',{maximumFractionDigits:2}):'—';
export const loadFingerprint = load => JSON.stringify(Object.entries(load??{}).sort(([a],[b])=>a.localeCompare(b)));
async function json(p) { try { return JSON.parse(await readFile(p,'utf8')); } catch { return null; } }
async function txt(p) { try { return (await readFile(p,'utf8')).trim(); } catch { return '미기록'; } }
export function summarize(rows, expected, smoke, comparison) {
  const modes = ['off','on'].map(mode=>{
    const runs=rows.filter(r=>r.mode===mode), ops=runs.reduce((s,r)=>s+r.operations,0);
    const perOp=k=>ops&&runs.every(r=>nonnegative(r[k]))?runs.reduce((s,r)=>s+r[k],0)/ops:null;
    return {mode,runs,operations:ops, valid:runs.length===expected && runs.every(r=>r.valid),
      p95:runs.length&&runs.every(r=>nonnegative(r.p95))?runs.reduce((s,r)=>s+r.p95,0)/runs.length:null,
      sqlPerOp:perOp('sql'),rowsPerOp:perOp('rows'),rankSqlPerOp:perOp('rankSql'),inventoryRowsPerOp:perOp('inventoryRows'),
      cancelP95:runs.length&&runs.every(r=>nonnegative(r.cancelP95))?runs.reduce((s,r)=>s+r.cancelP95,0)/runs.length:null,
      duplicateActive:runs.length&&runs.every(r=>nonnegative(r.validation?.duplicateActiveSeats))?Math.max(...runs.map(r=>r.validation.duplicateActiveSeats)):null,
      success:ops?runs.reduce((s,r)=>s+r.passes,0)/ops*100:null};
  });
  const valid=comparison && !smoke && modes.every(m=>m.valid);
  return {modes,valid};
}
export async function report(root, selection='focus', smoke=false, cacheMode='compare') {
  root=resolve(root); await mkdir(root,{recursive:true});
  const chosen=specs.filter(s=>selection==='focus'||s[0]===selection), items=[];
  const expected=smoke||cacheMode!=='compare'?1:2;
  for(const [id,name,description,section] of chosen) {
    const rows=[]; const base=join(root,section,'run');
    for(const run of (await readdir(base).catch(()=>[])).filter(n=>/^\d\d-(on|off)$/.test(n)).sort()) {
      const d=await json(join(base,run,id+'.json')); if(!d) continue;
      const m=d.metrics??{}, success=m.operation_success?.values??{}, operations=(success.passes??0)+(success.fails??0);
      const sql=await json(join(base,run,id+'.sql.json'));
      const warm=await json(join(base,run,'warmup-'+id+'.json'));
      const wm=warm?.metrics??{}, ws=wm.operation_success?.values;
      const warmValid=ws?.passes>0 && ws?.fails===0 && (wm.http_req_failed?.values?.rate??1)===0 && !(wm.dropped_iterations?.values?.count??0) && requestMetrics(warm).valid;
      const validation=id==='dispatch'?await json(join(base,run,'validation.json')):null;
      const evidence=await json(join(base,run,'backend-'+id+'.json'));
      const backendLog=await txt(join(base,run,'backend-'+id+'.log'));
      const p95=(id==='dispatch'?m.holding_observed_ms:m.operation_ms)?.values?.['p(95)'];
      const request=requestMetrics(d),cancelP95=m.cancel_ms?.values?.['p(95)'];
      // Failures are the measured outcome of dispatch, not missing measurements.
      // All planned flows must finish; an interrupted run still cannot be compared.
      const valid=operations>0 && !(m.dropped_iterations?.values?.count??0)
        && warmValid && !!evidence?.imageId && evidence.profile==='benchmark-metrics'
        && (id==='dispatch'
          ? operations===d.load?.dispatchCases && validation?.dispatchCases===operations
            && nonnegative(validation?.duplicateActiveSeats) && [0,99].includes(sql?.exitCode)
          : success.fails===0 && m.http_req_failed?.values?.rate===0 && !(m.wrong_results?.values?.count??0)
            && sql?.exitCode===0 && Number.isFinite(p95) && request.valid);
      rows.push({run,mode:run.endsWith('-on')?'on':'off',operations,passes:success.passes??0,p95:p95??null,
        sql:request.sql??null,rows:request.rows??null,rankSql:request.rankSql??null,inventoryRows:request.inventoryRows??null,
        cancelP95:cancelP95??null,serverSql:sql,valid,warmValid,validation,evidence,load:d.load,database:sql?.database,
        dropped:m.dropped_iterations?.values?.count??0,failures:success.fails??0,
        warmupOperations:ws?(ws.passes??0)+(ws.fails??0):null,
        deadlocks:backendLog==='미기록'?null:deadlockEvidence(backendLog),
        backendLogRaw:`${section}/run/${run}/backend-${id}.log`,
        raw:`${section}/run/${run}/${id}.json`,sqlRaw:`${section}/run/${run}/${id}.sql.json`});
    }
    const summary=summarize(rows,expected,smoke,cacheMode==='compare');
    // Every case must own a new backend and schema. Missing or reused evidence blocks comparison.
    const unique=rows.length>0 && new Set(rows.map(r=>r.evidence?.containerId)).size===rows.length
      && new Set(rows.map(r=>r.database)).size===rows.length;
    const sameLoad=rows.every(r=>r.load) && new Set(rows.map(r=>loadFingerprint(r.load))).size===1;
    const sameResources=new Set(rows.map(r=>JSON.stringify([r.evidence?.cpuLimit,r.evidence?.memoryLimitBytes]))).size===1;
    if(!unique||!sameLoad||!sameResources) {
      summary.valid=false;
      for(const m of summary.modes) m.valid=false;
    }
    if(id==='waiting'&&summary.modes[0].runs.length&&!(summary.modes[0].rankSqlPerOp>0)) {
      summary.modes[0].valid=false;summary.valid=false;
    }
    items.push({id,name,description,rows,...summary});
  }
  const selectedModes=cacheMode==='compare'?['off','on']:[cacheMode];
  const complete=items.every(i=>selectedModes.every(mode=>{
    const m=i.modes.find(m=>m.mode===mode);
    return m.valid&&(i.id!=='dispatch'||mode==='off'||m.success===100&&m.duplicateActive===0&&m.runs.every(r=>r.validation.assigned===r.operations));
  }));
  const meta={schemaVersion:5,instrumentation:'jdbc-request-v1',baseline:await txt(join(root,'baseline-commit.txt')),after:await txt(join(root,'source-commit.txt')),
    dirty:await txt(join(root,'source-status.txt')),smoke,cacheMode,selection,complete};
  await writeFile(join(root,'comparison.json'),JSON.stringify({meta,items},null,2));
  await writeFile(join(root,'report-source.mjs'),await readFile(new URL(import.meta.url),'utf8'));
  const delta=(v)=>v==null?'<span class="muted">비교 보류</span>':`<span class="${v>=0?'good':'bad'}">${fmt(Math.abs(v))}% ${v>=0?'감소':'증가'}</span>`;
  const metricDelta=(i,key)=>{
    if(!i.valid)return smoke&&i.modes.every(m=>m.valid)?'':delta(null);
    if(key==='success'){
      const points=i.modes[1].success-i.modes[0].success;
      return `<span class="${points>=0?'good':'bad'}">${fmt(Math.abs(points))}%p ${points>=0?'상승':'하락'}</span>`;
    }
    if(key==='duplicateActive')return `<span class="${i.modes[1].duplicateActive===0?'good':'bad'}">${i.modes[1].duplicateActive===0?'중복 0건 확인':'중복 발생'}</span>`;
    return delta(reduction(i,key));
  };
  const calculation=(m,key)=>{
    const field={sqlPerOp:'sql',rowsPerOp:'rows',rankSqlPerOp:'rankSql',inventoryRowsPerOp:'inventoryRows'}[key];
    if(!m.runs.length)return '측정 없음';
    if(field)return `총 ${fmt(total(m.runs,field))}${key==='sqlPerOp'||key==='rankSqlPerOp'?'회':'행'} ÷ 실제 요청 ${fmt(m.operations)}건`;
    if(key==='success')return `성공 ${fmt(total(m.runs,'passes'))}건 ÷ 시도 ${fmt(m.operations)}건 × 100`;
    return '실행 후 DB 검사에서 확인한 중복 선점 최댓값';
  };
  const bars=(item,key,unit)=>{
    const max=Math.max(...item.modes.map(m=>m[key]??0),1);
    return item.modes.map((m,i)=>`<div class="barrow"><span>${i?'개선 후':'개선 전'}</span><div class="track"><div class="bar ${i?'after':'before'}" style="width:${Math.max(0,(m[key]??0)/max*100)}%"></div></div><b>${fmt(m[key])} <small>${unit}</small></b></div>`).join('');
  };
  const deadlockPanel=i=>{
    if(i.id!=='dispatch'||!i.rows.some(r=>r.deadlocks?.warnings>0))return '';
    const [before,after]=i.modes;
    const warnings=m=>m.runs.length&&m.runs.every(r=>nonnegative(r.deadlocks?.warnings))
      ?m.runs.reduce((sum,r)=>sum+r.deadlocks.warnings,0):null;
    const verified=cacheMode==='compare'&&i.modes.every(m=>m.valid)&&warnings(before)>0
      &&total(before.runs,'failures')>0&&warnings(after)===0&&after.success===100&&after.duplicateActive===0
      &&after.runs.every(r=>r.validation?.assigned===r.operations);
    return `<section class="resolution ${verified?'verified':''}"><h3>${verified?`이번 실험: 개선 후 데드락 로그 0건 · ${fmt(after.operations)}건 모두 선점 완료`:'전후 데드락·완료 결과 확인 필요'}</h3>
      <p class="boundary">데드락 로그: 이전 ${fmt(warnings(before))}건 → 이후 ${warnings(after)==null?'미기록':`${fmt(warnings(after))}건`}. 이번 측정 조건에서 확인한 결과.</p></section>`;
  };
  const evidence=i=>`<details><summary>측정 근거 보기</summary>
    <p>${changes[i.id].interpretation}</p><p>${metricSpecs[i.id].note}</p>
    ${metricSpecs[i.id].metrics.map(([key,label])=>`<p><b>${label}</b><br>${i.modes.map((m,n)=>`${n?'개선 후':'개선 전'}: ${calculation(m,key)}`).join('<br>')}</p>`).join('')}
    ${i.id==='dispatch'?`<p>${i.modes.map((m,n)=>`${n?'개선 후':'개선 전'}: 전체 ${fmt(m.operations)}건 중 성공 ${fmt(total(m.runs,'passes'))}건 · 실패 ${fmt(total(m.runs,'failures'))}건`).join('<br>')}</p>`:''}
    ${i.rows.some(r=>r.deadlocks?.warnings>0)?`<p>${i.rows.some(r=>r.deadlocks?.warnings>0&&r.deadlocks.waitingRead)?'로그에서 순번 GET의 앞 대기자 FOR UPDATE 조회 중 데드락을 확인했다. ':''}정확한 행·인덱스 간 잠금 순환은 InnoDB 잠금 그래프가 없어 미확인이다. 데드락 로그 수는 Hibernate WARN 줄 수이며, 잠금 순환 개수가 아니다.</p>`:''}
    <p>${i.rows.map(r=>`${esc(r.run)}: <a href="${r.raw}">요청 결과</a> · <a href="${r.sqlRaw}">서버 SQL</a>${r.deadlocks?` · <a href="${r.backendLogRaw}">로그</a>`:''}`).join('<br>')}</p></details>`;
  const cards=items.map((i,n)=>`<article><div class="eyebrow">0${n+1}${selectedModes.every(mode=>i.modes.find(m=>m.mode===mode).valid)?'':' / 확인 필요'}</div><h2>${i.name}</h2>
    <div class="change"><div><h3>이전</h3><p>${changes[i.id].before}</p></div><div><h3>개선</h3><p>${changes[i.id].after}</p></div></div>
    ${metricSpecs[i.id].metrics.map(([key,label,unit])=>`<div class="metric"><h3>${label}</h3>${metricDelta(i,key)}</div>${bars(i,key,unit)}`).join('')}
    ${deadlockPanel(i)}${evidence(i)}</article>`).join('');
  await writeFile(join(root,'index.html'),`<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SmartTicketing | 개선 전후 성능</title>
  <style>*{box-sizing:border-box}body{margin:0;background:#f3f5f8;color:#172334;font:15px/1.6 system-ui,-apple-system,sans-serif}main{max-width:1240px;margin:auto;padding:40px 28px}header{margin-bottom:28px}.eyebrow{font-size:12px;font-weight:750;color:#687a90}h1{font-size:34px;letter-spacing:-1px;margin:8px 0}h2{font-size:23px;margin:4px 0 20px}h3{font-size:14px;margin:0}p{margin:8px 0}small,.muted{color:#687a90;font-weight:400}.badge{display:inline-block;padding:5px 11px;border-radius:20px;background:#dce9e7;color:#145b50;font-size:12px}.warning{background:#fff0d7;color:#805710}.grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}article{background:white;padding:26px;border:1px solid #e0e6ee;border-radius:16px}.change{display:grid;gap:10px}.change>div{display:grid;grid-template-columns:34px 1fr;gap:10px;padding:12px;background:#f3f5f8;border-radius:8px;font-size:13px}.change>div+div{background:#edf8f5}.change h3{font-size:13px}.change p{margin:0}.metric{display:flex;justify-content:space-between;gap:12px;margin:22px 0 10px;font-size:13px;font-weight:750}.barrow{display:grid;grid-template-columns:52px 1fr 98px;align-items:center;gap:12px;margin:8px 0;font-size:13px}.barrow b{text-align:right}.track{height:10px;background:#f1f4f8;border-radius:8px;overflow:hidden}.bar{height:100%;border-radius:8px}.before{background:#8897ad}.after{background:#0e9d83}.good{color:#087b64}.bad{color:#c84935}.resolution{padding:14px;background:#fff5e8;border-radius:10px;margin-top:20px}.resolution.verified{background:#edf8f5}.resolution h3{font-size:14px}.verified h3{color:#11624a}.boundary{font-size:12px;color:#687a90;margin-bottom:0}details{margin-top:20px;font-size:12px;color:#586a81}summary{cursor:pointer;color:#637790}details p{margin:12px 0}a{color:#087b64}.notes{margin-top:28px}code{overflow-wrap:anywhere}footer{margin-top:14px;font-size:12px}@media(max-width:800px){.grid{grid-template-columns:1fr}main{padding:24px 14px}h1{font-size:27px}article{padding:20px}}</style></head><body><main>
  <header><div class="eyebrow">SMARTTICKETING</div><h1>기능별 개선 전후 비교</h1><p class="muted">MySQL 기반 처리에 Redis와 조회·잠금 구조 개선을 적용한 결과</p>${!complete?'<span class="badge warning">미완료 또는 실패 · 비교 보류</span>':smoke?'':'<span class="badge">측정 및 정합성 검증 완료</span>'}</header>
  <div class="grid">${cards}</div>
  <details class="notes"><summary>공통 측정 조건 · 비교 코드</summary>
    <p>모든 회차는 120석. 전후 동일 데이터·부하·자원 조건으로 기능 결과를 검증한다. Java 21·MySQL 8.4·Redis 7·k6 컨테이너를 사용하며 입장 대기열과 로컬 L1 캐시는 제외한다.</p>
    <p>SQL과 결과 행은 HTTP 요청 스레드의 JDBC 계측값이다. 별도 워커 SQL과 DB 내부 스캔량은 포함하지 않는다. Redis뿐 아니라 DB 조회·잠금·Outbox 변경도 포함한 종합 비교다.</p>
    <p>스모크 결과는 제한된 조건에서의 관찰이며 운영 성능이나 모든 부하에서의 무오류를 보장하지 않는다. 자세한 부하 설정과 원본 수치는 결과 JSON에 보관한다.</p>
    <p>이전 코드: <code>${esc(meta.baseline)}</code><br>현재 코드: <code>${esc(meta.after)}</code>${meta.dirty?' + 작업 폴더 변경 포함':''}</p>
    <p><a href="images.txt">이미지 정보</a> · <a href="instrumentation.sha256">계측 소스 체크섬</a> · <a href="source.patch">측정 코드 변경</a></p>
  </details><footer><a href="comparison.json">전체 결과 JSON</a></footer></main></body></html>`);
  return complete;
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  if(!await report(process.argv[2],process.argv[3],process.argv[4]==='true',process.argv[5])) process.exitCode=2;
}
