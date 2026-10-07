import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { normalize, loginUsers, runBench } from './run.mjs';
import { writeVisualReport, openReport } from './visual-report.mjs';

export function validatePair(three, five) {
    const a = three.preferences, b = five.preferences;
    if (three.id === five.id || a.theaters.length !== 3 || b.theaters.length !== 5
        || new Set(b.theaters.map(t => t.id)).size !== 5
        || JSON.stringify(a.theaters.map(t => t.id)) !== JSON.stringify(b.theaters.slice(0,3).map(t => t.id))
        || JSON.stringify(a.seats) !== JSON.stringify(b.seats)) {
        throw new Error('서로 다른 3개·5개 테스트 계정이 필요합니다. 3개는 5개의 상위 3개, 좌석 선호 순서는 동일해야 합니다.');
    }
}

export async function compare(config, credentials, directory, log = console.log) {
    if (!credentials?.three || !credentials?.five) throw new Error('계정 파일에 three와 five의 loginId/password를 지정하세요.');
    const single = {...config, concurrency:[1]};
    const [three] = await loginUsers(single, [credentials.three]);
    const [five] = await loginUsers(single, [credentials.five]);
    validatePair(three, five);
    await mkdir(directory, {recursive:true});
    const cases = [{name:'three', count:3, user:three}, {name:'five', count:5, user:five}];
    await writeFile(join(directory,'comparison.json'), JSON.stringify({config:single, cases:cases.map(c=>({name:c.name,count:c.count,userId:c.user.id}))},null,2));
    const summaries = [];
    try {
        for (const c of cases) {
            log(`영화관 ${c.count}개 측정`);
            const result = await runBench({...single, expectedPreferences:c.user.preferences}, [credentials[c.name]], join(directory,c.name), {log});
            summaries.push(...result.map(s=>({...s,theaterCount:c.count,label:config.label})));
        }
    } finally {
        // Include partial/failed runs too, so fast failures cannot disappear from the comparison.
        const all = [];
        for (const c of cases) {
            try {
                const report = JSON.parse(await readFile(join(directory,c.name,'summary.json'),'utf8'));
                all.push(...report.summary.map(s=>({...s,theaterCount:c.count,label:config.label,note:report.note})));
            } catch (error) { if (error.code !== 'ENOENT') throw error; }
        }
        await writeFile(join(directory,'summary.json'),JSON.stringify(all,null,2));
        const quote = v => '"'+String(v??'').replace(/^[=+\-@]/,"'$&").replaceAll('"','""')+'"';
        const rows = [['label','theaters','scenario','attempts','successes','failures','failureRate','meanMs','medianMs','p95Ms','cleanupFailures','status'],
            ...all.map(s=>[s.label,s.theaterCount,s.scenario,s.attempts,s.successes,s.failures,s.attempts?s.failures/s.attempts:0,s.flow.mean,s.flow.p50,s.flow.p95,s.cleanupFailures,s.note])];
        await writeFile(join(directory,'summary.csv'),'\uFEFF'+rows.map(r=>r.map(quote).join(',')).join('\r\n')+'\r\n');
        try { await writeVisualReport(directory); } catch(error) { log(`그래프 생성 실패: ${error.message}. CSV 결과는 저장되었습니다.`); }
    }
    return summaries;
}

async function main() {
    const args = process.argv.slice(2);
    const file = args.find(a=>!a.startsWith('--'));
    const recovery = args.find(a=>a.startsWith('--recover='))?.slice(10);
    if (!file || args.some(a=>a.startsWith('--') && a!=='--run' && !a.startsWith('--recover=')) || (recovery && args.includes('--run'))) {
        throw new Error('node scripts/smart-booking-benchmark/compare.mjs <설정.json> [--run | --recover=결과폴더]');
    }
    const raw = JSON.parse(await readFile(resolve(file),'utf8'));
    const config = recovery ? JSON.parse(await readFile(join(resolve(recovery),'comparison.json'),'utf8')).config : normalize(raw);
    if (config.concurrency.length !== 1 || config.concurrency[0] !== 1) throw new Error('3개/5개 비교는 concurrency: [1]로 실행하세요.');
    console.log({label:config.label,date:config.viewingDate,movieId:config.movieId,ranges:config.ranges,
        theaters:[3,5],samplesPerCondition:config.rounds*config.iterations,warmups:config.warmupIterations});
    if (!args.includes('--run') && !recovery) { console.log('계획 확인 완료. --run을 붙이면 실제 생성·조회·취소를 실행합니다.');return; }
    const credentials = JSON.parse(await readFile(resolve(dirname(resolve(file)),raw.usersFile),'utf8'));
    if (recovery) {
        for (const name of ['three','five']) {
            const directory = join(resolve(recovery),name);
            try { await access(join(directory,'manifest.json')); } catch (error) { if(error.code==='ENOENT') continue;throw error; }
            const saved = JSON.parse(await readFile(join(directory,'manifest.json'),'utf8'));
            delete saved.expectedPreferences;
            await runBench(saved,[credentials[name]],directory,{recover:true});
        }
        return;
    }
    const directory = resolve('benchmark-results',`smart-${config.label}-${new Date().toISOString().replaceAll(':','-')}-${randomUUID().slice(0,8)}`);
    console.log(`결과: ${directory}`);
    try { await compare(config,credentials,directory); }
    finally {
        const html=join(directory,'report.html');
        try { await access(html); console.log(`그래프: ${html}`); openReport(html); } catch { /* No report before setup completes. */ }
    }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    main().catch(error=>{console.error(error.message);process.exitCode=1;});
}
