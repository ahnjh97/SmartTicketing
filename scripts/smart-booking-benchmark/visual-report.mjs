import { readFile, writeFile, readdir, stat } from 'node:fs/promises';
import { join, dirname, basename, resolve } from 'node:path';
import { spawn } from 'node:child_process';

const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number = n => Number.isFinite(n) ? n.toLocaleString('ko-KR',{maximumFractionDigits:1}) : '—';
export function conditionKey(config, preferences) {
    return JSON.stringify([config.baseUrl, config.movieId, config.viewingDate, config.partySize, config.ranges,
        config.concurrency, config.rounds, config.iterations, config.warmupIterations, config.pauseMs, config.timeoutMs, preferences]);
}
async function loadRun(directory) {
    const read = async file => JSON.parse(await readFile(join(directory,file),'utf8'));
    const {config} = await read('comparison.json');
    const rows = await read('summary.json');
    const preferences=[];
    for(const name of ['three','five']) {
        try { preferences.push((await read(`${name}/manifest.json`)).users.map(u=>u.preferences)); }
        catch(error) { if(error.code!=='ENOENT')throw error;preferences.push(null); }
    }
    return {directory,config,rows,key:conditionKey(config,preferences),time:(await stat(join(directory,'summary.json'))).mtimeMs};
}
export function renderReport(current, partner) {
    const runs = [current,...(partner?[partner]:[])].sort((a,b)=>a.config.label==='before'?-1:b.config.label==='before'?1:0);
    const metrics=[['mean','평균'],['p50','중앙값'],['p95','p95']];
    const max=Math.max(1,...runs.flatMap(r=>r.rows.flatMap(s=>metrics.map(([k])=>s.flow[k]??0))));
    const scenarios=[...new Set(runs.flatMap(r=>r.rows.map(s=>s.scenario)))];
    const label=r=>r.config.label==='before'?'개선 전':r.config.label==='after'?'개선 후':r.config.label;
    const charts=scenarios.map(scenario=>`<section><h2>${escape(scenario)}</h2><div class="grid">${[3,5].map(count=>`<article><h3>극장 ${count}개</h3>${metrics.map(([key,title])=>`<h4>${title}</h4>${runs.map(run=>{
        const row=run.rows.find(s=>s.scenario===scenario&&s.theaterCount===count),value=row?.flow[key];
        return `<div class="barrow"><span>${escape(label(run))}</span><div class="track"><div class="bar ${run.config.label==='after'?'after':''}" style="width:${Number.isFinite(value)?Math.max(0,value/max*100):0}%"></div></div><b>${number(value)} ms</b></div>`;
    }).join('')}`).join('')}${(()=>{
        const before=runs.find(r=>r.config.label==='before')?.rows.find(s=>s.scenario===scenario&&s.theaterCount===count);
        const after=runs.find(r=>r.config.label==='after')?.rows.find(s=>s.scenario===scenario&&s.theaterCount===count);
        const a=before?.flow.p50,b=after?.flow.p50;
        return a>0&&Number.isFinite(b)?`<p class="delta">중앙값 ${number(Math.abs((a-b)/a*100))}% ${b<=a?'단축':'증가'}</p>`:'';
    })()}</article>`).join('')}</div></section>`).join('');
    return `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>스마트예매 측정 결과</title>
<style>body{font-family:system-ui,sans-serif;background:#f4f6fb;color:#182339;margin:0;padding:36px}main{max-width:1120px;margin:auto}h1{font-size:30px}p{line-height:1.7;color:#536079}h2{margin-top:32px}h3{margin:0}h4{margin:22px 0 8px;font-size:14px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}article{background:white;border:1px solid #e0e5ed;border-radius:16px;padding:24px}.barrow{display:grid;grid-template-columns:64px 1fr 92px;gap:10px;align-items:center;margin:10px 0;font-size:13px}.track{background:#eef1f6;height:18px;border-radius:5px;overflow:hidden}.bar{height:100%;background:#7c8bb7}.after{background:#18a78b}.delta{font-weight:700;color:#182339}table{width:100%;border-collapse:collapse;background:white;font-size:14px}td,th{text-align:left;padding:12px;border-bottom:1px solid #e0e5ed}.scroll{overflow:auto}code{overflow-wrap:anywhere}.notice{padding:14px;background:#e8edf7;border-radius:10px}@media(max-width:720px){body{padding:16px}.grid{grid-template-columns:1fr}article{padding:16px}}</style>
<main><h1>스마트예매 측정 결과</h1><p>영화 #${escape(current.config.movieId)} · ${escape(current.config.viewingDate)} · ${escape(current.config.partySize)}명<br>후보 상세 수신까지 필요한 API 시간 · 낮을수록 빠름</p>
<p class="notice">${partner?'같은 설정·선호 조건의 가장 최근 반대 실험과 비교합니다.':'일치하는 개선 전·후 기록이 없어 이번 결과만 표시합니다.'} DB·캐시 상태의 동일함까지 보장하지는 않습니다.</p>
${charts||'<p>완료된 측정 기록이 없습니다.</p>'}<h2>성공·실패</h2><div class="scroll"><table><thead><tr><th>실험</th><th>조건</th><th>성공 / 시도</th><th>실패율</th><th>정리 실패</th><th>상태</th></tr></thead><tbody>${runs.flatMap(run=>run.rows.map(s=>`<tr><td>${escape(label(run))}</td><td>${s.theaterCount}개 · ${escape(s.scenario)}</td><td>${s.successes} / ${s.attempts}</td><td>${number(s.attempts?s.failures/s.attempts*100:0)}%</td><td>${s.cleanupFailures}</td><td>${escape(s.note)}</td></tr>`)).join('')}</tbody></table></div>
<p>워밍업·실패 요청은 시간 통계에서 제외합니다. 30건의 p95는 참고값입니다. 실패율과 정리 실패도 함께 확인하세요.</p><h2>사용한 결과</h2>${runs.map(r=>`<p>${escape(label(r))}: <code>${escape(basename(r.directory))}</code></p>`).join('')}<p>각 결과 폴더의 summary.csv에서 수치를 확인할 수 있습니다.</p></main></html>`;
}
export async function writeVisualReport(directory) {
    const current=await loadRun(directory);
    let partner=null;
    if(['before','after'].includes(current.config.label)) {
        for(const entry of await readdir(dirname(directory),{withFileTypes:true})) {
            if(!entry.isDirectory()||!entry.name.startsWith('smart-'))continue;
            const path=join(dirname(directory),entry.name);
            if(resolve(path)===resolve(directory))continue;
            try {
                const run=await loadRun(path);
                if(run.config.label===(current.config.label==='before'?'after':'before')&&run.key===current.key&&(!partner||run.time>partner.time))partner=run;
            } catch { /* Other/older benchmark formats are not comparison candidates. */ }
        }
    }
    const path=join(directory,'report.html');
    await writeFile(path,renderReport(current,partner));
    return path;
}
export function openReport(path) {
    if(process.platform!=='win32')return;
    const child=spawn('powershell.exe',['-NoProfile','-NonInteractive','-Command','Start-Process -FilePath $env:SMART_BENCH_REPORT'],
        {env:{...process.env,SMART_BENCH_REPORT:resolve(path)},windowsHide:true,stdio:'ignore'});
    child.on('error',()=>console.error(`브라우저 자동 열기 실패. 직접 여세요: ${path}`));
    child.on('exit',code=>{if(code)console.error(`브라우저 자동 열기 실패. 직접 여세요: ${path}`);});
}
