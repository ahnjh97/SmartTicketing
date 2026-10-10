import test from 'node:test';
import assert from 'node:assert/strict';
import {summarize,report,loadFingerprint,requestMetrics,metricSpecs,reduction,deadlockEvidence} from './core-report.mjs';
import {mkdtemp,readFile,rm,mkdir,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
const row=(mode,p95,sql,operations=100)=>({mode,p95,sql,operations,passes:operations,valid:true});
test('deadlock warnings are counted once instead of counting repeated stack-trace text',()=>{
  const log=['WARN thread-1 : Deadlock found when trying to get lock',
    'ERROR : Deadlock found when trying to get lock',
    'Exception: Deadlock found when trying to get lock',
    'select ... coalesce(wq1_0.zone_queue_number,wq1_0.queue_number)<? order by wq1_0.id for update of wq1_0',
    'at smartticketing.service.BookingWaitingService.get(BookingWaitingService.java:172)'].join('\n');
  assert.deepEqual(deadlockEvidence(log),{warnings:1,waitingRead:true});
  assert.deepEqual(deadlockEvidence('no recorded errors'),{warnings:0,waitingRead:false});
});
test('load comparison ignores JSON property order but detects different rates',()=>{
  assert.equal(loadFingerprint({rate:2,duration:'3s'}),loadFingerprint({duration:'3s',rate:2}));
  assert.notEqual(loadFingerprint({rate:2,duration:'3s'}),loadFingerprint({duration:'3s',rate:3}));
});
test('each hypothesis has one or two relevant primary metrics',()=>{
  assert.equal(metricSpecs.seats.metrics.length,1);
  assert.equal(metricSpecs.showtimes.metrics[0][0],'inventoryRowsPerOp');
  assert.deepEqual(metricSpecs.dispatch.metrics.map(m=>m[0]),['success','duplicateActive']);
  for(const s of Object.values(metricSpecs)) assert.ok(s.metrics.length>=1&&s.metrics.length<=2);
});
test('missing request counters are not replaced with global server SQL',()=>{
  assert.equal(requestMetrics({metrics:{operation_success:{values:{passes:7,fails:0}}}}).valid,false);
  const metrics={operation_success:{values:{passes:2,fails:0}},jdbc_instrumented:{values:{rate:1}},
    jdbc_samples:{values:{count:2}},request_sql:{values:{count:0}},request_rows:{values:{count:0}},
    rank_sql:{values:{count:0}},inventory_rows:{values:{count:0}}};
  assert.equal(requestMetrics({metrics}).valid,true);
  metrics.jdbc_samples.values.count=1;
  assert.equal(requestMetrics({metrics}).valid,false);
});
test('weighted per-request rows and separate cancellation tradeoff',()=>{
  const s=summarize([{...row('off',100,100),rows:24000,inventoryRows:24000,rankSql:100,cancelP95:80},
    {...row('on',150,50,200),rows:2400,inventoryRows:2400,rankSql:0,cancelP95:40}],1,false,true);
  assert.equal(s.modes[1].inventoryRowsPerOp,12);
  assert.equal(reduction(s,'inventoryRowsPerOp'),95);
  assert.equal(reduction(s,'cancelP95'),50);
  assert.equal(reduction(s,'p95'),-50);
});
test('uses SQL per operation and balanced repeats, not total counts',()=>{
  const s=summarize([row('off',100,1000),row('off',120,1000),row('on',50,400,200),row('on',60,400,200)],2,false,true);
  assert.equal(s.valid,true);assert.equal(reduction(s,'p95'),50);assert.equal(reduction(s,'sqlPerOp'),80);
});
test('smoke, failures, missing repeats and single mode cannot claim improvement',()=>{
  const rows=[row('off',100,1000),row('on',50,100)];
  for(const s of [summarize(rows,1,true,true),summarize(rows,2,false,true),summarize(rows,1,false,false),
    summarize([rows[0],{...rows[1],valid:false}],1,false,true)]) {
    assert.equal(s.valid,false);assert.equal(reduction(s,'p95'),null);assert.equal(reduction(s,'sqlPerOp'),null);
  }
});
test('regression remains a negative reduction',()=>{
  assert.equal(reduction(summarize([row('off',50,100),row('on',100,200)],1,false,true),'p95'),-100);
});
test('dispatch failures are outcomes, current failures and missing flows still fail the run',async()=>{
  const root=await mkdtemp(join(tmpdir(),'core-dispatch-'));
  try {
    for(const [run,passes,fails] of [['01-off',1,3],['02-on',4,0]]) {
      const dir=join(root,'booking','run',run);await mkdir(dir,{recursive:true});
      const write=(name,d)=>writeFile(join(dir,name+'.json'),JSON.stringify(d));
      await write('dispatch',{load:{dispatchCases:4,pollMs:100},metrics:{operation_success:{values:{passes,fails}},http_req_failed:{values:{rate:fails/4}}}});
      await write('warmup-dispatch',{metrics:{operation_success:{values:{passes:1,fails:0}},http_req_failed:{values:{rate:0}},
        jdbc_instrumented:{values:{rate:1}},jdbc_samples:{values:{count:1}},request_sql:{values:{count:1}},
        request_rows:{values:{count:1}},rank_sql:{values:{count:1}},inventory_rows:{values:{count:0}}}});
      await write('dispatch.sql',{exitCode:fails?99:0,database:run});
      await write('backend-dispatch',{containerId:run,imageId:'image',profile:'benchmark-metrics',cpuLimit:0,memoryLimitBytes:0});
      await write('validation',{dispatchCases:4,assigned:passes,duplicateActiveSeats:0});
    }
    assert.equal(await report(root,'dispatch',true,'compare'),true);
    const result=JSON.parse(await readFile(join(root,'comparison.json'),'utf8'));
    assert.equal(result.items[0].modes[0].success,25);
    assert.equal(result.items[0].modes[0].p95,null);
    const html=await readFile(join(root,'index.html'),'utf8');
    assert.match(html,/성공 1건 ÷ 시도 4건 × 100/);
    assert.match(html,/성공 4건 ÷ 시도 4건 × 100/);
    assert.match(html,/성공 1건 · 실패 3건/);
    assert.match(html,/성공 4건 · 실패 0건/);
    assert.match(html,/<h2>취소 후 대기자 자동 선점<\/h2>/);
    assert.doesNotMatch(html,/Redis OFF|Redis ON|반복 실행|예열/);
    assert.match(html,/Outbox 하나가 데드락을 해결했다고 단정하지 않는다/);
    assert.doesNotMatch(html,/실패 로그에서 확인한 지점/);
    const beforeLog=join(root,'booking/run/01-off/backend-dispatch.log');
    const afterLog=join(root,'booking/run/02-on/backend-dispatch.log');
    await writeFile(beforeLog,'WARN : Deadlock found when trying to get lock\n'.repeat(3)
      +'coalesce(wq1_0.zone_queue_number,wq1_0.queue_number)<? for update of wq1_0\nBookingWaitingService.get(BookingWaitingService.java:172)');
    await writeFile(afterLog,'Application started');
    assert.equal(await report(root,'dispatch',true,'compare'),true);
    const verified=await readFile(join(root,'index.html'),'utf8');
    assert.match(verified,/이번 실험: 개선 후 데드락 로그 0건 · 4건 모두 선점 완료/);
    assert.match(verified,/전체 4건 중 성공 1건 · 실패 3건/);
    assert.match(verified,/데드락 로그: 이전 3건 → 이후 0건/);
    assert.doesNotMatch(verified,/스모크 결과 · 성능 결론 보류/);
    await rm(afterLog);
    await report(root,'dispatch',true,'compare');
    const missingLog=await readFile(join(root,'index.html'),'utf8');
    assert.match(missingLog,/이후 미기록/);
    assert.doesNotMatch(missingLog,/이번 실험: 개선 후 데드락 로그 0건/);
    await writeFile(afterLog,'WARN : Deadlock found when trying to get lock');
    await report(root,'dispatch',true,'compare');
    assert.doesNotMatch(await readFile(join(root,'index.html'),'utf8'),/이번 실험: 개선 후 데드락 로그 0건/);
    await writeFile(afterLog,'Application started');
    const path=join(root,'booking/run/02-on/dispatch.json');
    const data=JSON.parse(await readFile(path,'utf8'));
    data.metrics.operation_success.values={passes:3,fails:1};await writeFile(path,JSON.stringify(data));
    assert.equal(await report(root,'dispatch',true,'compare'),false);
    assert.doesNotMatch(await readFile(join(root,'index.html'),'utf8'),/4건 모두 선점 완료/);
    data.metrics.operation_success.values={passes:3,fails:0};await writeFile(path,JSON.stringify(data));
    assert.equal(await report(root,'dispatch',true,'compare'),false);
  } finally {await rm(root,{recursive:true,force:true});}
});
test('reader HTML keeps counts in collapsed evidence and omits repeat and warmup counts',async()=>{
  const root=await mkdtemp(join(tmpdir(),'core-counts-'));
  try {
    for(const [run,operations,sql] of [['01-off',61,122],['02-on',60,6]]) {
      const dir=join(root,'seats','run',run);await mkdir(dir,{recursive:true});
      const data={load:{rate:10,duration:'6s'},metrics:{operation_success:{values:{passes:operations,fails:0}},
        request_sql:{values:{count:sql}},request_rows:{values:{count:sql}},rank_sql:{values:{count:0}},inventory_rows:{values:{count:0}}}};
      await writeFile(join(dir,'seats.json'),JSON.stringify(data));
      await writeFile(join(dir,'warmup-seats.json'),JSON.stringify({metrics:{operation_success:{values:{passes:55,fails:0}}}}));
    }
    // Missing image/instrumentation evidence still blocks a performance claim.
    assert.equal(await report(root,'seats',true,'compare'),false);
    const html=await readFile(join(root,'index.html'),'utf8');
    assert.match(html,/총 122회 ÷ 실제 요청 61건/);
    assert.match(html,/총 6회 ÷ 실제 요청 60건/);
    assert.doesNotMatch(html,/예열|반복 실행|55건/);
    const visible=html.replace(/<details\b[^>]*>[\s\S]*?<\/details>/g,'');
    assert.doesNotMatch(visible,/총 122회|실제 요청 61건/);
    assert.match(visible,/<h2>좌석도 조회<\/h2>/);
    assert.doesNotMatch(html,/NaN|Infinity/);
  } finally {await rm(root,{recursive:true,force:true});}
});
test('empty failed run still produces reviewable HTML without invented values',async()=>{
  const root=await mkdtemp(join(tmpdir(),'core-report-'));
  try { assert.equal(await report(root,'focus',false,'compare'),false);
    const html=await readFile(join(root,'index.html'),'utf8');
    assert.match(html,/미완료 또는 실패/);assert.match(html,/비교 보류/);assert.doesNotMatch(html,/NaN|Infinity/);
    assert.equal(JSON.parse(await readFile(join(root,'comparison.json'),'utf8')).items.length,4);
    for(const name of ['대기순번 조회','좌석도 조회','상영시간표 조회','취소 후 대기자 자동 선점'])assert.ok(html.includes(`<h2>${name}</h2>`));
  } finally { await rm(root,{recursive:true,force:true}); }
});
