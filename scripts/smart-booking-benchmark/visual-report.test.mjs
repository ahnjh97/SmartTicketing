import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,mkdir,writeFile,readFile,rm } from 'node:fs/promises';
import { join,dirname,resolve,basename } from 'node:path';
import { tmpdir } from 'node:os';
import { renderReport,writeVisualReport } from './visual-report.mjs';

const config={baseUrl:'http://localhost:8080',movieId:14,viewingDate:'2099-01-01',partySize:6,ranges:[{name:'day',from:'08:00',to:'00:00'}],rounds:3,iterations:10};
const rows=value=>[3,5].map(theaterCount=>({theaterCount,scenario:'day/c1',flow:{mean:value,p50:value,p95:value*2},attempts:30,successes:29,failures:1,cleanupFailures:0,note:'완료'}));
test('HTML shows both metrics and failures, escapes text and distinguishes slowdown',()=>{
    const before={config:{...config,label:'before'},rows:rows(100),directory:'before'};
    const after={config:{...config,label:'after'},rows:rows(150),directory:'after'};
    after.rows[0].note='<script>alert(1)</script>';
    const html=renderReport(after,before);
    assert.ok(html.includes('50% 증가'));assert.ok(html.includes('29 / 30'));assert.ok(html.includes('3.3%'));
    assert.ok(html.includes('&lt;script&gt;'));assert.ok(!html.includes('<script>'));
    assert.ok(renderReport({...before,rows:[]},null).includes('완료된 측정 기록이 없습니다'));
});
test('only matching before/after settings and preferences are paired',async()=>{
    const root=await mkdtemp(join(tmpdir(),'smart-visual-test-'));
    async function run(name,label,value,prefs=[1,2,3]) {
        const dir=join(root,name);await mkdir(dir,{recursive:true});
        await writeFile(join(dir,'comparison.json'),JSON.stringify({config:{...config,label}}));
        await writeFile(join(dir,'summary.json'),JSON.stringify(rows(value)));
        for(const section of ['three','five']) {
            await mkdir(join(dir,section));
            await writeFile(join(dir,section,'manifest.json'),JSON.stringify({users:[{preferences:prefs}]}));
        }
        return dir;
    }
    try {
        const after=await run('smart-after','after',50);
        assert.ok((await readFile(await writeVisualReport(after),'utf8')).includes('이번 결과만'));
        await run('smart-before','before',100);
        await run('smart-before-wrong','before',1000,[5,4,3]);
        const html=await readFile(await writeVisualReport(after),'utf8');
        assert.ok(html.includes('50% 단축'));assert.ok(!html.includes('smart-before-wrong'));
    } finally {
        assert.equal(dirname(resolve(root)),resolve(tmpdir()));assert.ok(basename(root).startsWith('smart-visual-test-'));
        await rm(root,{recursive:true,force:true});
    }
});
