import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve,dirname,basename} from 'node:path';
import {spawnSync} from 'node:child_process';

for(const [name,smoke,duration,eligible] of [
    ['smoke never produces a performance percentage',true,'3s',false],
    ['adequate repeated runs preserve conflicting directions',false,'60s',true],
    ['short measurement is rejected despite successful requests',false,'3s',false],
]) test(name,async()=>{
    const root=await mkdtemp(join(tmpdir(),'query-report-'));
    try {
        const source=join(root,'source'),target=join(root,'report');
        await mkdir(source);
        const executions=[];
        const modes=smoke?['OFF','ON']:['OFF','ON','ON','OFF','OFF','ON','ON','OFF'];
        for(const [offset,mode] of modes.entries()) {
            const index=offset+1,p95=mode==='OFF'?10:index%2===0?100:1;
            const run=`0${index}-${mode.toLowerCase()}`;
            await mkdir(join(source,run));
            const data={metrics:{httpReqs:{count:smoke?6:500,rate:2},httpReqFailed:{rate:0},checks:{fails:0},
                                httpReqDuration:{'p(95)':p95,'p(99)':p95}},load:{rate:2,duration}};
            await writeFile(join(source,run,'main.json'),JSON.stringify(data));
            data.load.duration=smoke?'3s':'30s';
            await writeFile(join(source,run,'warmup.json'),JSON.stringify(data));
            executions.push({id:'main',name:'메인',run,mode,file:run+'/main.json',warmupFile:run+'/warmup.json',
                exitCode:0,warmupExitCode:0,pid:index,database:run,
                cache:{local:true,localTtlMs:500,redisTtlMs:2000,redis:mode==='ON'}});
        }
        await writeFile(join(source,'environment.json'),JSON.stringify({smoke,plannedExecutions:modes.length,os:'Linux',java:'21',cacheMode:'compare'}));
        await writeFile(join(source,'executions.json'),JSON.stringify(executions));
        const result=spawnSync(process.execPath,[resolve('scripts/benchmark/query-report.mjs'),source,target],{encoding:'utf8'});
        assert.equal(result.status,0,result.stderr);
        const report=JSON.parse(await readFile(join(target,'results.json'),'utf8'));
        assert.equal(report.complete,true);
        assert.equal(report.pairs[0].valid,true);
        assert.equal(report.pairs[0].performanceEligible,eligible);
        if(eligible) {
            assert.equal(report.pairs[0].pairedP95ChangesPercent.length,4);
            assert.ok(report.pairs[0].observation.includes('방향 다름'));
        } else assert.equal(report.pairs[0].change,null);
        if(smoke) assert.ok((await readFile(join(target,'index.html'),'utf8')).includes('기능 확인 전용'));
    } finally {
        assert.equal(dirname(resolve(root)),resolve(tmpdir()));
        assert.ok(basename(root).startsWith('query-report-'));
        await rm(root,{recursive:true,force:true});
    }
});
