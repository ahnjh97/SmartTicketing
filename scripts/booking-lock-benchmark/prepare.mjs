import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { dirname, resolve } from 'node:path';

const baseUrl = (process.env.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
let showtimeId = Number(process.env.SHOWTIME_ID);
let movieId = Number(process.env.MOVIE_ID);
let theaterId = Number(process.env.THEATER_ID);
let viewingDate = process.env.VIEWING_DATE;
const vus = Number(process.env.VUS || 20);
const partySize = Number(process.env.PARTY_SIZE || 1);
const out = resolve(process.env.OUT || '.local/booking-lock-benchmark/manifest.json');
const seats = ['MIDDLE_FRONT','MIDDLE_MIDDLE','MIDDLE_REAR','SIDE_FRONT','SIDE_MIDDLE','SIDE_REAR'];

function requireValue(ok, message) { if (!ok) throw new Error(message); }
requireValue(Number.isInteger(vus) && vus >= 2 && vus <= 100, 'VUS는 2~100으로 지정하세요.');
requireValue(Number.isInteger(partySize) && partySize >= 1 && partySize <= 6, 'PARTY_SIZE는 1~6으로 지정하세요.');

async function request(path, {token, body, method, key} = {}) {
  const response = await fetch(baseUrl + path, {
    method: method || (body === undefined ? 'GET' : 'POST'),
    headers: {'Content-Type':'application/json', ...(token ? {Authorization:'Bearer '+token}:{}),
      ...(key ? {'Idempotency-Key':key}:{})},
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  if (!response.ok) throw new Error(`${method || (body === undefined ? 'GET' : 'POST')} ${path} -> HTTP ${response.status}: ${JSON.stringify(data).slice(0,500)}`);
  return data;
}

const scenarioFrom = process.env.SCENARIO_FROM;
if (scenarioFrom) {
  const prior = JSON.parse(await (await import('node:fs/promises')).readFile(resolve(scenarioFrom), 'utf8'));
  showtimeId = Number(prior.showtimeId); movieId = Number(prior.movieId);
  theaterId = Number(prior.theaterId); viewingDate = prior.viewingDate;
}
if (![showtimeId,movieId,theaterId].every(n => Number.isSafeInteger(n) && n > 0)
    || !/^\d{4}-\d{2}-\d{2}$/.test(viewingDate || '')) {
  const date = new Date(Date.now() + 33 * 3600000).toISOString().slice(0,10);
  console.log(`Auto-discovering a bookable showtime for ${date}, party size ${partySize}...`);
  let selected = null;
  for (let page=0; page<100 && !selected; page++) {
    const movies = await request(`/api/movies?page=${page}&size=20`);
    const items = movies.items;
    requireValue(Array.isArray(items), '영화 목록 조회 형식이 예상과 다릅니다. SHOWTIME_ID/MOVIE_ID/THEATER_ID/VIEWING_DATE를 직접 지정하세요.');
    for (const movie of items) {
      const shows = await request(`/api/showtimes?movieId=${movie.id}&date=${date}&startFrom=08:00&startUntil=00:00`);
      const showItems = shows.items;
      if (!Array.isArray(showItems)) continue;
      selected = showItems.find(s => s.layoutComplete && (s.bookablePartySizes?.includes(partySize) || s.maxContiguousSeats >= partySize));
      if (selected) { movieId = Number(movie.id); break; }
    }
    if (items.length < 20 || (page+1)*20 >= (movies.totalElements ?? 0)) break;
  }
  requireValue(selected, '내일 조건에 맞는 회차를 자동으로 찾지 못했습니다. SHOWTIME_ID, MOVIE_ID, THEATER_ID, VIEWING_DATE를 직접 지정하세요.');
  showtimeId = Number(selected.id);
  theaterId = Number(selected.theaterId);
  viewingDate = date;
}
requireValue(Number.isSafeInteger(showtimeId) && showtimeId > 0 && Number.isSafeInteger(movieId) && movieId > 0
  && Number.isSafeInteger(theaterId) && theaterId > 0 && /^\d{4}-\d{2}-\d{2}$/.test(viewingDate),
  '회차/영화/극장/날짜 설정이 올바르지 않습니다.');

// UserService requires 3-5 preferred theaters; include the target theater and
// fill the remaining slots from the active theater catalog.
const theaterCatalog = await request('/api/theaters?page=0&size=20');
const theaterItems = theaterCatalog.items;
requireValue(Array.isArray(theaterItems), '영화관 목록 응답 형식이 예상과 다릅니다.');
const preferredTheaterIds = [...new Set([
  theaterId,
  ...theaterItems.map(t => Number(t.id)).filter(id => Number.isSafeInteger(id) && id > 0 && id !== theaterId),
])].slice(0, 3);
requireValue(preferredTheaterIds.length === 3, '선호 영화관 3곳을 찾지 못했습니다. 활성 영화관 데이터를 확인하세요.');

const users = [];
for (let i=0; i<vus; i++) {
  const credential = {loginId:`bench-lock-${Date.now()}-${i}-${randomUUID().slice(0,8)}@example.test`, password:`Bench-${randomUUID()}-Aa9!`};
  const signup = await request('/api/auth/signup', {body:{...credential, name:'잠금성능측정', birthDate:'1990-01-01'}});
  const token = signup.accessToken;
  requireValue(typeof token === 'string' && token.length > 0, '회원가입 응답에 accessToken이 없습니다.');
  await request('/api/users/me', {token, method:'PATCH', body:{
    birthDate:'1990-01-01', preferredTheaterIds, preferredSeatPositions:seats
  }});
  const group = await request('/api/booking-groups', {token, key:randomUUID(), body:{
    entryPoint:'THEATER_SMART', movieId, viewingDate, partySize, selectedShowtimeId:showtimeId,
    audience:{adultCount:partySize, youthCount:0, companionsEligible:false, guardianAccompanying:false}
  }});
  const groupId = group.id ?? group.groupId;
  requireValue(Number.isSafeInteger(groupId) && groupId > 0, '예매 그룹 생성 응답에서 group ID를 찾지 못했습니다.');
  users.push({token, groupId, userIndex:i+1});
  console.log(`prepared ${i+1}/${vus}: group=${groupId}`);
}
await mkdir(dirname(out), {recursive:true});
await writeFile(out, JSON.stringify({baseUrl, showtimeId, movieId, theaterId, viewingDate, partySize, createdAt:new Date().toISOString(), users}, null, 2)+'\n', {mode:0o600});
console.log(`Manifest written: ${out}`);
console.log('주의: 계정/그룹은 측정용으로 생성되며, 기존 계정과 예약은 변경하지 않습니다. 측정 후 생성된 그룹을 앱에서 취소하거나 만료되도록 두세요.');
