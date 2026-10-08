import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {resolve,join,dirname,basename} from 'node:path';
import vm from 'node:vm';
import {buildDashboard} from './dashboard.mjs';

test('dashboard isolates runs, switches tabs and preserves failures without exposing fixture data',async()=>{
    const parent=resolve('build');await mkdir(parent,{recursive:true});
    const root=await mkdtemp(join(parent,'dashboard-test-'));
    const put=async(path,data)=>{await mkdir(dirname(join(root,path)),{recursive:true});await writeFile(join(root,path),JSON.stringify(data));};
    try {
        for(const [id,mode,day,valid] of [['new-on','ON','09',true],['old-off','OFF','08',false]]) {
            await put(`${id}/results.json`,{env:{startedAt:`2026-10-${day}T12:00:00+09:00`,suite:'main',cacheMode:mode.toLowerCase(),smoke:false,
                os:'</script><img src=x onerror=alert(1)>',java:'21'},complete:true,
                rows:[{id:'main',run:'01-'+mode.toLowerCase(),mode,p95:100,p99:150,requests:10,rps:5,errorRate:valid?0:0.1,checkFails:valid?0:1,dropped:0,valid,file:`01-${mode.toLowerCase()}/main.json`}]});
            await put(`${id}/raw/01-${mode.toLowerCase()}/main.json`,{});
            await put(`${id}/run/fixture.json`,{token:'DO_NOT_INCLUDE_THIS_SECRET'});
        }
        await put('booking-smoke/run/environment.json',{startedAt:'2026-10-07T12:00:00+09:00',os:'Linux',java:'21'});
        await put('booking-smoke/run/01-off/dispatch.json',{metrics:{operation_success:{values:{passes:4,fails:0}},operation_ms:{values:{'p(95)':10}},http_reqs:{values:{count:12,rate:5}},http_req_failed:{values:{rate:0}}}});
        await put('booking-smoke/run/01-off/validation.json',{assigned:4,dispatchCases:4,duplicateActiveSeats:0});
        await put('bad/run-info.json',{startedAt:'2026-10-06T12:00:00+09:00',exitCode:1});
        await writeFile(join(root,'bad/results.json'),'{bad json');
        const data=await buildDashboard(root);
        assert.equal(data.records.length,4);
        assert.equal(data.records.find(r=>r.id==='old-off').failed,true);
        assert.equal(data.records.find(r=>r.id==='bad').failed,true);
        assert.equal(data.records.find(r=>r.id==='booking-smoke').smoke,true);
        const html=await readFile(join(root,'index.html'),'utf8');
        assert.ok(!html.includes('DO_NOT_INCLUDE_THIS_SECRET'));
        assert.ok(!html.includes('</script><img'));
        const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
        const elements=new Map();
        function element(id){if(!elements.has(id))elements.set(id,{innerHTML:'',value:id==='kind'?'all':'',events:{},setAttribute(){},focus(){},addEventListener(name,fn){this.events[name]=fn;}});return elements.get(id);}
        const context=vm.createContext({document:{getElementById:element},location:{hash:''},history:{replaceState(){}},URLSearchParams});
        vm.runInContext(script,context);
        element('tabs').events.click({target:{closest:()=>({dataset:{tab:'main'}})}});
        assert.equal(element('run').value,'new-on');
        assert.ok(element('panel').innerHTML.includes('단독 측정'));
        assert.ok(element('panel').innerHTML.includes('&lt;/script&gt;'));
        element('run').value='old-off';element('run').events.change();
        assert.ok(element('panel').innerHTML.includes('비교 보류'));
        element('tabs').events.click({target:{closest:()=>({dataset:{tab:'booking'}})}});
        assert.equal(element('run').value,'booking-smoke');
        assert.ok(element('panel').innerHTML.includes('성능 개선율을 해석하지 마세요'));
        element('kind').value='load';element('kind').events.change();
        assert.ok(element('panel').innerHTML.includes('해당하는 결과가 없습니다'));
    } finally {
        assert.equal(dirname(resolve(root)),parent);assert.ok(basename(root).startsWith('dashboard-test-'));
        await rm(root,{recursive:true,force:true});
    }
});
