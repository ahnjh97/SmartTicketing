import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { normalize, request } from './run.mjs';

const root = fileURLToPath(new URL('../../',import.meta.url));
const local = join(root,'.local','smart-quick');
const seats = ['MIDDLE_MIDDLE','MIDDLE_REAR','SIDE_MIDDLE','MIDDLE_FRONT','SIDE_REAR','SIDE_FRONT'];
const settings = {baseUrl:'http://127.0.0.1:8080',timeoutMs:60000};
const success = r => r.status >= 200 && r.status < 300;
async function load(path) {
    try {return JSON.parse(await readFile(path,'utf8'));} catch(e) {if(e.code==='ENOENT')return null;throw e;}
}
async function save(path,value) {await writeFile(path,JSON.stringify(value,null,2)+'\n');}

export function selectScenario(items, date) {
    const movies = new Map();
    for (const s of items) {
        if (!s.layoutComplete || !Number.isSafeInteger(s.movieId) || !Number.isSafeInteger(s.theaterId)
            || !(s.bookablePartySizes?.includes(6) || s.maxContiguousSeats>=6)) continue;
        if (!movies.has(s.movieId)) movies.set(s.movieId,new Set());
        movies.get(s.movieId).add(s.theaterId);
    }
    const found = [...movies].filter(([,ids])=>ids.size>=5).sort((a,b)=>b[1].size-a[1].size || a[0]-b[0])[0];
    if(!found)throw new Error('내일 6명 예매가 가능한 극장 5곳의 회차를 찾지 못했습니다. 로컬 상영 데이터 준비 상태를 확인하세요.');
    return {movieId:found[0],viewingDate:date,theaters:[...found[1]].sort((a,b)=>a-b).slice(0,5),partySize:6,
        ranges:[{name:'day',from:'08:00',to:'00:00'}]};
}

export async function prepare(api, directory, date) {
    await mkdir(directory,{recursive:true});
    const selectionPath=join(directory,'selection.json');
    let selection=await load(selectionPath);
    if(!selection) {
        for(let page=0;page<100&&!selection;page++) {
            const movies=await api(`/api/movies?page=${page}&size=20`);
            if(!success(movies)||!Array.isArray(movies.data?.items))throw new Error('로컬 API 서버(localhost:8080)를 먼저 실행하세요. 영화 조회에 실패했습니다.');
            for(const movie of movies.data.items) {
                const response=await api(`/api/showtimes?movieId=${movie.id}&date=${date}&startFrom=08:00&startUntil=00:00`);
                if(!success(response)||!Array.isArray(response.data?.items))throw new Error('상영 회차 조회에 실패했습니다.');
                try {selection=selectScenario(response.data.items,date);break;} catch { /* Try the next catalog movie. */ }
            }
            if(movies.data.items.length<20||(page+1)*20>=movies.data.totalElements)break;
        }
        if(!selection)throw new Error('내일 6명 예매가 가능한 극장 5곳의 회차가 없습니다. 로컬 상영 데이터 준비 상태를 확인하세요.');
        await save(selectionPath,selection);
    }
    // Pin the actual date and theater order so before/after cannot silently use different scenarios.
    normalize({...settings,...selection,label:'before'});
    const credentialsPath=join(directory,'users.json');
    let credentials=await load(credentialsPath);
    if(!credentials) {
        credentials=Object.fromEntries(['three','five'].map(name=>[name,{loginId:`bench-${name}-${randomUUID()}@example.test`,password:randomUUID()}]));
        await save(credentialsPath,credentials);
    }
    for(const [name,count] of [['three',3],['five',5]]) {
        const credential=credentials[name];
        let login=await api('/api/auth/login',null,credential);
        if(!success(login)) {
            if(![400,401,404].includes(login.status))throw new Error(`측정 계정 로그인 실패 (${login.status})`);
            const signup=await api('/api/auth/signup',null,{...credential,name:'속도측정',birthDate:'1990-01-01'});
            if(!success(signup))throw new Error(`측정 계정 자동 생성 실패 (${signup.status})`);
            login=await api('/api/auth/login',null,credential);
        }
        if(!success(login)||!login.data?.accessToken)throw new Error('측정 계정 로그인 실패');
        const token=login.data.accessToken;
        const active=await api('/api/booking-groups/active',token);
        if(!success(active)||!Array.isArray(active.data)||active.data.length)throw new Error('이전 측정의 미정리 후보가 있습니다. 결과 폴더로 --recover를 실행하세요.');
        const updated=await api('/api/users/me',token,{preferredTheaterIds:selection.theaters.slice(0,count),preferredSeatPositions:seats},'PATCH');
        if(!success(updated))throw new Error(`측정 계정 선호 설정 실패 (${updated.status})`);
    }
    return {...settings,...selection,concurrency:[1],rounds:3,iterations:10,warmupIterations:1,pauseMs:500,usersFile:'users.json'};
}

async function main() {
    const args=process.argv.slice(2);
    const label=args.find(a=>!a.startsWith('--'))||'before';
    const recovery=args.find(a=>a.startsWith('--recover='));
    if(!['before','after'].includes(label)||args.some(a=>a.startsWith('--')&&!a.startsWith('--recover=')))throw new Error('benchmark-smart.cmd [before|after] [--recover=결과폴더]');
    const configPath=join(local,'config.json');
    if(!recovery) {
        console.log('로컬 회차 선택 → 측정 계정/선호 자동 준비 → 극장 3개·5개 각각 30회 측정');
        const date=new Date(Date.now()+33*3600000).toISOString().slice(0,10);
        const config=await prepare((path,token,body,method)=>request(settings,path,token,body,undefined,method),local,date);
        await save(configPath,{...config,label});
    }
    const result=spawnSync(process.execPath,[join(root,'scripts/smart-booking-benchmark/compare.mjs'),configPath,recovery||'--run'],{cwd:root,stdio:'inherit'});
    if(result.error)throw result.error;
    process.exitCode=result.status??1;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href) main().catch(e=>{console.error(e.message);process.exitCode=1;});
