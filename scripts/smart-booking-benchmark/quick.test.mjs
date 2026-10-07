import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,rm,readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join,dirname,resolve,basename } from 'node:path';
import { prepare,selectScenario } from './quick.mjs';

const shows=Array.from({length:5},(_,i)=>({movieId:14,theaterId:i+1,layoutComplete:true,bookablePartySizes:[6]}));
test('automatic selection requires five theaters and real six-seat capacity',()=>{
    assert.deepEqual(selectScenario(shows,'2099-01-01').theaters,[1,2,3,4,5]);
    assert.throws(()=>selectScenario(shows.slice(0,4),'2099-01-01'));
    assert.throws(()=>selectScenario(shows.map(s=>({...s,bookablePartySizes:[2]})),'2099-01-01'));
});
test('quick setup creates and configures two accounts, reuses pinned selection and never logs secrets',async()=>{
    const dir=await mkdtemp(join(tmpdir(),'smart-quick-test-'));
    const accounts=new Set(),updates=[];let catalogs=0,signups=0;
    const api=async(path,token,body,method)=>{
        if(path.startsWith('/api/movies'))return {status:200,data:{items:[{id:14}],totalElements:1}};
        if(path.startsWith('/api/showtimes')) {catalogs++;return {status:200,data:{items:shows}};}
        if(path==='/api/auth/signup') {accounts.add(body.loginId);signups++;return {status:201};}
        if(path==='/api/auth/login')return accounts.has(body.loginId)?{status:200,data:{accessToken:'test'}}:{status:401};
        if(path==='/api/booking-groups/active')return {status:200,data:[]};
        if(path==='/api/users/me') {assert.equal(method,'PATCH');updates.push(body);return {status:200};}
        throw new Error(path);
    };
    try {
        const first=await prepare(api,dir,'2099-01-01');
        const second=await prepare(api,dir,'2099-01-02');
        assert.equal(catalogs,1);assert.equal(signups,2);
        assert.equal(first.viewingDate,second.viewingDate);
        assert.deepEqual(updates.map(u=>u.preferredTheaterIds.length),[3,5,3,5]);
        assert.equal(first.rounds*first.iterations,30);
        assert.ok(!(await readFile(join(dir,'selection.json'),'utf8')).includes('password'));
        await assert.rejects(prepare(async(...args)=>args[0]==='/api/booking-groups/active'?{status:200,data:[{id:9}]}:api(...args),dir,'2099-01-01'),/미정리/);
    } finally {
        assert.equal(dirname(resolve(dir)),resolve(tmpdir()));assert.ok(basename(dir).startsWith('smart-quick-test-'));
        await rm(dir,{recursive:true,force:true});
    }
});
