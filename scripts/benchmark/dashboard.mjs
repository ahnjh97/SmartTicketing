import {readFile,readdir,writeFile,rename,mkdir,stat} from 'node:fs/promises';
import {resolve,join,relative,sep,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const here=dirname(fileURLToPath(import.meta.url));
const categories=[['booking','예매'],['main','메인'],['movies','영화'],['theaters','극장'],['showtimes','상영시간'],['seats','좌석'],['distance','거리'],['login','로그인']];
const labels={'waiting-100':'대기 순번 · 100 VU','waiting-400':'대기 순번 · 400 VU','waiting-800':'대기 순번 · 800 VU','status-400':'상태 조회 · 400 VU',dispatch:'취소표 배정'};
const aliases={'main-only':'main','common-legacy':'legacy',common:'legacy'};
const number=v=>typeof v==='number'&&Number.isFinite(v)?v:null;
async function exists(path){try{return (await stat(path)).isFile();}catch{return false;}}
async function json(path){try{return JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''));}catch(e){if(e.code==='ENOENT')return null;throw e;}}

export async function buildDashboard(root) {
    root=resolve(root); await mkdir(root,{recursive:true});
    const records=[];
    for(const entry of await readdir(root,{withFileTypes:true})) {
        if(!entry.isDirectory())continue;
        const folder=join(root,entry.name),info=await json(join(folder,'run-info.json')).catch(()=>null);
        const record={id:entry.name,date:info?.startedAt??'',suite:info?.suite??'',requestedMode:info?.cacheMode??'',
            exitCode:info?.exitCode??null,rows:[],reports:[],notes:[],os:'',java:'',smoke:info?.smoke??null,isolated:false};
        const link=async path=>{
            const rel=relative(root,resolve(path));
            if(rel.startsWith('..')||rel.split(sep).includes('..')||!await exists(path))return null;
            return rel.split(sep).map(encodeURIComponent).join('/');
        };
        let recognized=!!info;
        for(const section of ['', 'booking', 'queries']) {
            const base=join(folder,section);
            let result;
            try {result=await json(join(base,'results.json'));}
            catch {record.notes.push(`${section||'기본'} 결과 JSON을 읽지 못했습니다.`);recognized=true;continue;}
            const env=result?.env??await json(join(base,'run/environment.json')).catch(()=>null);
            if(!env&&!result)continue;
            recognized=true;
            record.date ||= env?.startedAt??''; record.os ||= env?.os??''; record.java ||= env?.java??'';
            record.suite ||= env?.suite??'booking'; record.requestedMode ||= env?.cacheMode??'compare';
            record.isolated ||= !!(env?.freshJvmPerCase||env?.fixture?.freshJvmPerCase);
            if((env?.execution??env?.fixture?.execution)==='production-prebuilt-jar'&&!record.notes.includes('배포용 JAR · k6 별도 컨테이너 · Redis AOF ON'))record.notes.push('배포용 JAR · k6 별도 컨테이너 · Redis AOF ON');
            if(env?.smoke!=null)record.smoke=record.smoke===true||env.smoke;
            const report=await link(join(base,'index.html'));
            if(report)record.reports.push({name:section==='booking'?'예매 상세':section==='queries'?'조회·로그인 상세':'상세 보고서',href:report});
            if(result?.complete===false||result?.fair===false)record.notes.push('미완료 또는 비교 조건 불일치가 기록된 실행입니다.');
            if(Array.isArray(result?.rows)) {
                if(record.smoke==null)record.smoke=false;
                for(const row of result.rows) {
                    const category=row.scenario?'booking':aliases[row.id]??row.id??'legacy';
                    const id=row.scenario??row.id??'unknown';
                    let raw=null;
                    const file=row.file??`${row.run}/${id}.json`;
                    if(typeof file==='string'&&!file.includes('..')&&!file.includes(':')&&!file.startsWith('/'))raw=await link(join(base,'raw',file));
                    const failure=number(row.failed??row.checkFails??0)??0,errorRate=number(row.httpFailed??row.errorRate);
                    const dropped=number(row.dropped??0)??0,wrong=number(row.wrong??0)??0;
                    const blockedComparison=result.complete===false||result.fair===false||
                        ((env?.cacheMode??'compare')==='compare'&&id!=='login'&&result.pairs?.some(p=>(p.id??p.scenario)===id&&p.valid===false));
                    const valid=!blockedComparison&&row.valid!==false&&row.warmupValid!==false&&failure===0&&errorRate===0&&dropped===0&&wrong===0&&(row.exitCode??0)===0;
                    record.rows.push({category,id,label:labels[id]??categories.find(c=>c[0]===category)?.[1]??'기존 통합 조회',
                        run:row.run??'',mode:row.mode??'N/A',p95:number(row.p95),p99:number(row.p99),requests:number(row.requests),
                        rps:number(row.rps),errorRate,failure,dropped,wrong,valid,raw,
                        load:row.load?`${row.load.rate}/s · ${row.load.duration} · ${row.load.preAllocatedVUs}~${row.load.maxVUs} VU`:null});
                }
            } else if(env && (section==='booking'||!env.suite)) {
                // Booking smoke has raw k6 summaries but intentionally no detailed HTML.
                const runs=await readdir(join(base,'run'),{withFileTypes:true}).catch(()=>[]);
                for(const run of runs.filter(r=>r.isDirectory()&&/^\d+-(on|off)$/.test(r.name))) {
                    const summary=await json(join(base,'run',run.name,'dispatch.json')).catch(()=>null);
                    const validation=await json(join(base,'run',run.name,'validation.json')).catch(()=>null);
                    if(!summary)continue;
                    const m=summary.metrics??{},success=m.operation_success?.values??{};
                    record.rows.push({category:'booking',id:'dispatch',label:'취소표 배정 · 동작 확인',run:run.name,
                        mode:run.name.endsWith('-on')?'ON':'OFF',p95:number(m.operation_ms?.values?.['p(95)']),p99:number(m.operation_ms?.values?.['p(99)']),
                        requests:number(m.http_reqs?.values?.count),rps:number(m.http_reqs?.values?.rate),
                        errorRate:number(m.http_req_failed?.values?.rate),failure:success.fails??0,dropped:0,wrong:m.wrong_results?.values?.count??0,
                        valid:success.passes>0&&success.fails===0&&m.http_req_failed?.values?.rate===0&&validation?.assigned===validation?.dispatchCases&&validation?.duplicateActiveSeats===0,
                        raw:await link(join(base,'run',run.name,'dispatch.json')),load:null});
                }
                if(record.rows.length)record.smoke=true;
            }
        }
        if(!record.reports.length)for(const name of ['index.html','report.html']) {
            const href=await link(join(folder,name));
            if(href){record.reports.push({name:'기존 상세 보고서',href});recognized=true;break;}
        }
        record.log=await link(join(folder,'runner.log'))??await link(join(folder,'services.log'));
        if(!recognized&&!record.log)continue;
        if(!record.rows.length)record.notes.push('통합 표에 표시할 측정값이 없습니다. 상세 보고서 또는 로그를 확인하세요.');
        record.failed=record.exitCode!=null&&record.exitCode!==0||record.rows.some(r=>!r.valid)||record.notes.some(n=>n.includes('미완료')||n.includes('읽지 못'));
        record.date ||= (await stat(folder)).mtime.toISOString();
        records.push(record);
    }
    records.sort((a,b)=>Date.parse(b.date)-Date.parse(a.date)||b.id.localeCompare(a.id));
    const payload={generatedAt:new Date().toISOString(),categories,records};
    const template=await readFile(join(here,'dashboard.html'),'utf8');
    const html=template.replace('/*REPORT_DATA*/',JSON.stringify(payload).replace(/</g,'\\u003c').replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029'));
    const temporary=join(root,`.dashboard-${process.pid}.tmp`);
    await writeFile(temporary,html);await rename(temporary,join(root,'index.html'));
    console.log(`Dashboard: ${join(root,'index.html')} (${records.length} runs)`);
    return payload;
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))await buildDashboard(process.argv[2]??'benchmark-results');
