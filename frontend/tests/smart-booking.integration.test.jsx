import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import App from '../src/App.jsx';
import { seoulDate } from '../src/booking/state.js';
import { openTossPayment } from '../src/booking/tossPayments.js';
import { bookingApi } from '../src/api/booking.js';

vi.mock('../src/booking/tossPayments.js', () => ({ openTossPayment: vi.fn() }));

const day=seoulDate(new Date(Date.now()+86400000));
const member={id:1,nickname:'관객',birthDate:'1990-01-01',address:'서울',preferredTheaters:[71,72,73].map(theaterId=>({theaterId})),preferredSeats:[{position:'MIDDLE_MIDDLE',priority:1}]};
const movie={id:41,movieId:41,title:'서울의 밤',rating:'ALL',backdropUrl:'/wide.jpg'};
const show={id:91,movieId:41,theaterId:71,screenName:'1관',startTime:`${day}T23:00:00+09:00`,endTime:`${day}T23:59:00+09:00`,layoutComplete:true,availableSeats:0,totalSeats:18,maxContiguousSeats:0};
const moviePath=`/movies?movie=41&date=${day}&adult=2&youth=0&from=22:00&until=02:00&entry=MOVIE_SMART`;
const theaterPath=`/theaters?theater=71&movie=41&showtime=91&date=${day}&adult=2&youth=0&entry=THEATER_SMART`;
const response=(body,status=200)=>new Response(JSON.stringify(body),{status});
let plan,created,lost,pending;
function initial() {
    return {id:301,movieTitle:movie.title,partySize:2,candidates:['FAST','BALANCED','PREFERRED'].map((kind,i)=>({
        groupId:401+i,kind,movieTitle:movie.title,partySize:2,zone:['SIDE_MIDDLE','MIDDLE_REAR','MIDDLE_MIDDLE'][i],showtimeId:91,
        theaterName:'서울 극장',screenName:'1관',startTime:show.startTime,endTime:show.endTime,status:'ACTIVE',payment:null,
        waiting:{groupId:401+i,groupStatus:'ACTIVE',activeReservationId:null,choices:[],items:[{id:601+i,showtimeId:91,queueNumber:i+4,aheadCount:i,status:'WAITING',seatZone:['SIDE_MIDDLE','MIDDLE_REAR','MIDDLE_MIDDLE'][i]}]},
    }))};
}
function allocate(index) {
    const c=plan.candidates[index];c.status='HOLDING';c.waiting.items[0].status='HOLDING';
    c.payment={status:null,ticket:null,reservation:{id:501+index,groupId:c.groupId,status:'PENDING',movieTitle:movie.title,
        theaterName:c.theaterName,screenName:c.screenName,startTime:show.startTime,endTime:show.endTime,
        seatIds:[index*2+1,index*2+2],seatLabels:[`A${index*2+1}`,`A${index*2+2}`],totalAmount:20000,
        expiresAt:`${day}T09:05:00+09:00`,serverTime:`${day}T09:00:00+09:00`}};
}
async function api(url, options = {}) {
    if(url==='/api/users/me') return response(member);
    if(url==='/api/main') return response({ nowShowing: [movie], comingSoon: [] });
    if(url.startsWith('/api/movies?')) return response({items:[movie]});
    if(url==='/api/movies/41') return response(movie);
    if(url.startsWith('/api/theaters?')) return response({items:[]});
    if(url==='/api/theaters/71') return response({id:71,name:'서울 극장'});
    if(url.startsWith('/api/theaters/71/movies')) return response({items:[movie]});
    if(url.startsWith('/api/showtimes/availability?')) return response({available:true,latestStartTime:show.startTime});
    if(url.startsWith('/api/showtimes?')) return response({items:[show]});
    if(url==='/api/smart-booking-candidates' && options.method === 'POST') {
        created++; if(pending) return pending;if(lost && created===1) throw new TypeError('응답 유실');return response({ groupIds: plan.candidates.map(c=>c.groupId) },201);
    }
    if(url.startsWith('/api/smart-booking-candidates')) {
        const selected = new URL(url, 'http://local').searchParams.get('selected');
        return response({ candidates: plan.candidates.filter(c=>String(c.groupId)===selected || ['ACTIVE','HOLDING'].includes(c.status)), batches: plan.batches });
    }
    if(url==='/api/booking-groups/active') return response([]);
    if(url.startsWith('/api/reservations/')) {
        const id=Number(url.split('/')[3]); const c=plan.candidates.find(c=>c.payment?.reservation.id===id);
        if(url.endsWith('/toss-orders')) return response({ orderId: `st-${id}`, orderName: movie.title, amount: c.payment.reservation.totalAmount });
        if(url.endsWith('/toss-confirmations')) {
            expect(JSON.parse(options.body)).toEqual({ orderId: `st-${id}`, paymentKey: `test-payment-${id}`, amount: c.payment.reservation.totalAmount });
            c.status='COMPLETED';c.payment.status='SUCCESS';c.payment.reservation.status='CONFIRMED';c.payment.ticket={ticketNumber:`ST-${id}`};c.waiting.items[0].status='COMPLETED';return response(c.payment,201);
        }
        if(url.endsWith('/cancel')) {c.status='CANCELLED';c.payment.reservation.status='CANCELLED';c.waiting.items[0].status='CANCELLED';return response(c.payment);}
        if(url.endsWith('/payment')) return response(c.payment);
    }
    if(url.match(/^\/api\/booking-groups\/\d+\/cancel$/)) {
        const c=plan.candidates.find(c=>c.groupId===Number(url.split('/')[3]));c.status='CANCELLED';c.waiting.items[0].status='CANCELLED';return response(c.waiting);
    }
    throw new Error(`Unexpected ${url}`);
}
function Probe(){const location=useLocation();const navigate=useNavigate();return <><output data-testid="url">{location.pathname}{location.search}</output><button onClick={()=>navigate('/movies?movie=41')}>조건 화면으로 이동</button></>;}
function mount(path=moviePath){return render(<StrictMode><MemoryRouter initialEntries={[path]}><App/><Probe/></MemoryRouter></StrictMode>);}
const nativeShow=HTMLDialogElement.prototype.showModal,nativeClose=HTMLDialogElement.prototype.close;
beforeEach(()=>{openTossPayment.mockReset().mockResolvedValue(undefined);localStorage.clear();sessionStorage.clear();localStorage.setItem('accessToken','test');plan=initial();created=0;lost=false;pending=null;vi.stubGlobal('fetch',vi.fn(api));
    HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};});
afterEach(()=>{cleanup();vi.unstubAllGlobals();HTMLDialogElement.prototype.showModal=nativeShow;HTMLDialogElement.prototype.close=nativeClose;});
const candidates=()=>within(screen.getByRole('region',{name:'대기 및 선점 목록'}));
async function loaded(){await screen.findByRole('complementary',{name:'좌석 선정 후보'});}
const choose=label=>fireEvent.click(candidates().getByRole('button',{name:new RegExp(label)}));

async function paySelectedCandidate() {
    const calls = openTossPayment.mock.calls.length;
    fireEvent.click(await screen.findByRole('button', { name: '결제', exact: true }));
    await waitFor(() => expect(openTossPayment).toHaveBeenCalledTimes(calls + 1));
    const [order, reservationId, userId, candidateId] = openTossPayment.mock.calls.at(-1);
    const candidate = plan.candidates.find(c => c.groupId === candidateId);
    expect(userId).toBe(member.id);
    expect(candidate.payment.reservation.id).toBe(reservationId);
    expect(candidate.payment.reservation.status).toBe('PENDING');
    await bookingApi.confirmToss(reservationId, {
        orderId: order.orderId, paymentKey: `test-payment-${reservationId}`, amount: order.amount,
    }, crypto.randomUUID());
    const path = new URL(screen.getByTestId('url').textContent, 'http://local');
    path.searchParams.set('candidate', candidateId);
    path.searchParams.set('reservation', reservationId);
    path.searchParams.set('tossResult', 'success');
    cleanup(); mount(path.pathname + path.search);
    await screen.findByRole('heading', { name: '예매가 완료되었습니다' });
}

test('movie smart creates three independent zone candidates once and persists the plan URL',async()=>{
    mount(moviePath.replace('&entry=MOVIE_SMART',''));
    await waitFor(()=>expect(screen.getByRole('button',{name:'스마트예매'}).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'스마트예매'}));await loaded();
    expect(created).toBe(1);expect(candidates().getAllByRole('button')).toHaveLength(3);
    const body=JSON.parse(fetch.mock.calls.find(([url])=>url==='/api/smart-booking-candidates')[1].body);
    expect(body).toMatchObject({entryPoint:'MOVIE_SMART',movieId:41,partySize:2,startTimeFrom:'22:00',startTimeTo:'02:00',audience:{adultCount:2,youthCount:0}});
    expect(body.selectedShowtimeId).toBeUndefined();expect(screen.getByTestId('url').textContent).toContain('smart=1');
    expect(fetch.mock.calls.some(([url])=>url.endsWith('/smart-hold')||url.endsWith('/manual-hold'))).toBe(false);
    const path=screen.getByTestId('url').textContent;cleanup();mount(path);await loaded();expect(created).toBe(1);
});

test('embedded initial candidates render without an immediate status request',async()=>{
    fetch.mockImplementation((url,options={})=>url==='/api/smart-booking-candidates'&&options.method==='POST'
        ? Promise.resolve(response({groupIds:plan.candidates.map(c=>c.groupId),initial:plan},201)) : api(url,options));
    mount();await loaded();
    expect(candidates().getAllByRole('button')).toHaveLength(3);
    expect(fetch.mock.calls.filter(([url,options])=>url.startsWith('/api/smart-booking-candidates')&&options?.method!=='POST')).toHaveLength(0);
});

test('focus while a status request is pending waits for it before refreshing',async()=>{
    let resolveRead, reads=0;
    fetch.mockImplementation((url,options={})=>{
        if(url.startsWith('/api/smart-booking-candidates')&&options.method!=='POST') {
            reads++;
            if(reads===1)return new Promise(resolve=>{resolveRead=resolve;});
        }
        return api(url,options);
    });
    mount();await waitFor(()=>expect(reads).toBe(1));
    fireEvent(window,new Event('focus'));fireEvent(window,new Event('focus'));
    expect(reads).toBe(1);
    await act(async()=>resolveRead(response(plan)));
    await waitFor(()=>expect(reads).toBe(2));
});

test('sold-out theater smart creates candidates for its selected show without requiring a free seat',async()=>{
    mount(theaterPath.replace('&entry=THEATER_SMART',''));
    await waitFor(()=>expect(screen.getByRole('button',{name:'스마트예매'}).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'스마트예매'}));await loaded();
    const body=JSON.parse(fetch.mock.calls.find(([url])=>url==='/api/smart-booking-candidates')[1].body);
    expect(body).toMatchObject({entryPoint:'THEATER_SMART',selectedShowtimeId:91,partySize:2});expect(body.startTimeFrom).toBeUndefined();
    expect(candidates().getAllByRole('button')).toHaveLength(3);
    expect(candidates().queryByText('직접 고른 회차')).toBeNull();
    expect(screen.getByRole('region',{name:'후보 구역 대기'}).textContent).toContain('대기순서1번');
    expect(within(screen.getByRole('region',{name:'후보 구역 대기'})).getByText(day.replaceAll('-', '.'))).toBeTruthy();
});

test('paying the side retains the center queue, then center can be paid and only the side cancelled',async()=>{
    allocate(0);mount();await loaded();
    await paySelectedCandidate();
    choose('선호 좌석');expect(screen.getByRole('region',{name:'후보 구역 대기'}).textContent).toContain('대기순서');
    expect(screen.getByTestId('url').textContent).not.toContain('reservation=');
    expect(screen.getByTestId('url').textContent).not.toContain('tossResult=');
    expect(candidates().queryByRole('button',{name:/빠른 예매/})).toBeNull();
    allocate(2);fireEvent(window,new Event('focus'));
    await paySelectedCandidate();
    expect(plan.candidates[0].payment.reservation.status).toBe('CONFIRMED');expect(plan.candidates[2].payment.reservation.status).toBe('CONFIRMED');
    const paidPath = moviePath + '&smart=1&candidate=401&reservation=501&tossResult=success'; cleanup(); mount(paidPath); await screen.findByRole('heading',{name:'예매가 완료되었습니다'}); fireEvent.click(screen.getByRole('button',{name:'예매 전체 취소'}));
    fireEvent.click(screen.getByRole('button',{name:'전체 취소 확정'}));await screen.findByRole('heading',{name:'예매가 취소되었습니다'});
    expect(plan.candidates[2].payment.reservation.status).toBe('CONFIRMED');expect(plan.candidates[1].waiting.items[0].status).toBe('WAITING');
});

test('all three candidates can be paid independently',async()=>{
    [0,1,2].forEach(allocate);mount();await loaded();
    for(const label of ['빠른 예매','균형 추천','선호 좌석']){choose(label);await paySelectedCandidate();}
    expect(plan.candidates.every(c=>c.payment.reservation.status==='CONFIRMED')).toBe(true);
    expect(fetch.mock.calls.filter(([url])=>url.endsWith('/toss-confirmations'))).toHaveLength(3);
});

test('cancelling one zone queue leaves both other queues waiting',async()=>{
    mount();await loaded();fireEvent.click(screen.getByRole('button',{name:'대기 취소'}));
    const dialog = screen.getByRole('dialog', { name: '대기를 취소할까요?' });
    expect(fetch.mock.calls.some(([url])=>url.endsWith('/cancel'))).toBe(false);
    fireEvent.click(within(dialog).getByRole('button', { name: '유지하기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(fetch.mock.calls.some(([url])=>url.endsWith('/cancel'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '대기 취소' }));
    fireEvent.click(screen.getByRole('button',{name:'대기 취소 확정'}));await screen.findByRole('heading',{name:'대기 취소'});
    expect(plan.candidates.map(c=>c.waiting.items[0].status)).toEqual(['CANCELLED','WAITING','WAITING']);
});

test('a lost create response retries the same key and never presents a made-up hold',async()=>{
    lost=true;mount();await screen.findByText('응답 유실');expect(screen.queryByRole('button',{name:'결제', exact: true})).toBeNull();
    fireEvent.click(screen.getByRole('button',{name:'다시 확인'}));await loaded();
    const requests=fetch.mock.calls.filter(([url,options])=>url==='/api/smart-booking-candidates' && options.method==='POST');
    expect(requests).toHaveLength(2);expect(requests[0][1].headers['Idempotency-Key']).toBe(requests[1][1].headers['Idempotency-Key']);
});

test('navigation during creation does not replace the new selection with a stale plan',async()=>{
    let resolve;pending=new Promise(r=>{resolve=r;});mount();await waitFor(()=>expect(created).toBe(1));
    fireEvent.click(screen.getByRole('button',{name:'조건 화면으로 이동'}));await act(async()=>resolve(response(plan,201)));
    expect(screen.getByTestId('url').textContent).not.toContain('smart=1');
});

test('theater smart lists and manages existing holds across shows while creating only for the selected show', async () => {
    allocate(0);
    plan.candidates[0].showtimeId = 999;
    plan.candidates[0].theaterName = '다른 극장';
    plan.candidates[0].payment.reservation.theaterName = '다른 극장';
    allocate(1);
    plan.candidates[1].showtimeId = 92;
    fetch.mockImplementation((url, options = {}) => {
        if (url === '/api/smart-booking-candidates' && options.method === 'POST') {
            created++;
            return Promise.resolve(response({ groupIds: [403] }, 201));
        }
        return api(url, options);
    });
    mount(theaterPath);
    await loaded();
    expect(candidates().getAllByRole('button')).toHaveLength(3);
    expect(within(candidates().getByRole('group', { name: '신청 묶음 2' })).getAllByRole('button')).toHaveLength(2);
    expect(candidates().getByRole('button', { name: /선호 좌석/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '결제', exact: true })).toBeNull();
    const request = fetch.mock.calls.find(([url, options]) => url === '/api/smart-booking-candidates' && options?.method === 'POST');
    expect(JSON.parse(request[1].body)).toMatchObject({ entryPoint: 'THEATER_SMART', selectedShowtimeId: 91 });
    const path = screen.getByTestId('url').textContent;
    cleanup(); mount(path); await loaded();
    expect(candidates().getAllByRole('button')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: '결제', exact: true })).toBeNull();
    choose('다른 극장');
    await paySelectedCandidate();
    await screen.findByRole('heading', { name: '예매가 완료되었습니다' });
    expect(plan.candidates[0].payment.reservation.status).toBe('CONFIRMED');
    expect(plan.candidates[1].payment.reservation.status).toBe('PENDING');
    expect(plan.candidates[2].waiting.items[0].status).toBe('WAITING');
    expect(created).toBe(1);
});

test.each([moviePath, theaterPath])('new smart request separates older recommendations and keeps them manageable after reload: %s', async path => {
    const createdIds = plan.candidates.map(candidate => candidate.groupId);
    plan.candidates.push(...plan.candidates.slice(1).map((candidate, index) => ({
        ...candidate, groupId: 900 + index,
        waiting: { ...candidate.waiting, items: candidate.waiting.items.map(queue => ({ ...queue, id: 990 + index })) },
    })));
    fetch.mockImplementation((url, options = {}) => {
        if (url === '/api/smart-booking-candidates' && options.method === 'POST') {
            created++;
            return Promise.resolve(response({ groupIds: createdIds }, 201));
        }
        return api(url, options);
    });
    mount(path); await loaded();
    const currentGroup = () => within(candidates().getByRole('group', { name: '신청 묶음 1' }));
    const previousGroup = () => within(candidates().getByRole('group', { name: '신청 묶음 2' }));
    expect(currentGroup().getAllByRole('button')).toHaveLength(3);
    expect(previousGroup().getAllByRole('button')).toHaveLength(2);
    expect(currentGroup().getAllByRole('button', { name: /선호 좌석/ })).toHaveLength(1);
    expect(currentGroup().getAllByRole('button', { name: /균형 추천/ })).toHaveLength(1);
    const restoredPath = screen.getByTestId('url').textContent;
    cleanup(); mount(restoredPath); await loaded();
    expect(currentGroup().getAllByRole('button')).toHaveLength(3);
    expect(previousGroup().getAllByRole('button')).toHaveLength(2);
    fireEvent.click(currentGroup().getByRole('button', { name: /선호 좌석/ }));
    fireEvent.click(screen.getByRole('button', { name: '대기 취소' }));
    fireEvent.click(screen.getByRole('button', { name: '대기 취소 확정' }));
    await screen.findByRole('heading', { name: '대기 취소' });
    expect(currentGroup().getAllByRole('button')).toHaveLength(2);
    expect(plan.candidates.filter(candidate => candidate.groupId >= 900).every(candidate => candidate.waiting.items[0].status === 'WAITING')).toBe(true);
    fireEvent.click(previousGroup().getByRole('button', { name: /선호 좌석/ }));
    fireEvent.click(screen.getByRole('button', { name: '대기 취소' }));
    fireEvent.click(screen.getByRole('button', { name: '대기 취소 확정' }));
    await screen.findByRole('heading', { name: '대기 취소' });
    expect(previousGroup().getAllByRole('button')).toHaveLength(1);
    expect(currentGroup().getAllByRole('button')).toHaveLength(2);
    expect(created).toBe(1);
});

test('separate requests stay in distinct unlabeled groups after reopening the overview', async () => {
    plan.batches = [[403], [402], [401]];
    mount(moviePath + '&smart=1'); await loaded();
    const groups = candidates().getAllByRole('group');
    expect(groups).toHaveLength(3);
    expect(groups.map(group => within(group).getByRole('button').getAttribute('aria-label')))
        .toEqual([expect.stringContaining('선호 좌석'), expect.stringContaining('균형 추천'), expect.stringContaining('빠른 예매')]);
    expect(candidates().queryByText('이번 신청')).toBeNull();
    expect(candidates().queryByText('이전 신청')).toBeNull();
    expect(created).toBe(0);
});

test('narrow screens open the candidate list by count and close it after selecting a waiting card', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
    mount(); await loaded();
    expect(screen.queryByRole('region', { name: '대기 및 선점 목록' })).toBeNull();
    expect(screen.getByRole('region', { name: '후보 구역 대기' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '대기 및 선점 3개 목록 펼치기' }));
    choose('선호 좌석');
    expect(screen.queryByRole('region', { name: '대기 및 선점 목록' })).toBeNull();
    expect(screen.getByRole('region', { name: '후보 구역 대기' }).textContent).toContain('대기순서3번');
    expect(screen.queryByRole('link', { name: '내 대기 및 선점 모두 보기' })).toBeNull();
});

test('fewer than three valid candidates are shown honestly',async()=>{
    plan.candidates=plan.candidates.slice(0,1);mount();await loaded();expect(candidates().getAllByRole('button')).toHaveLength(1);
    expect(screen.getByRole('list', { name: '스마트예매 진행 단계' })).toBeTruthy();
});

test('refreshing a paid candidate restores all siblings and selected candidate',async()=>{
    allocate(2);mount();await loaded();choose('선호 좌석');await paySelectedCandidate();
    await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
    const path=screen.getByTestId('url').textContent;cleanup();mount(path);await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
    expect(candidates().getAllByRole('button')).toHaveLength(2);expect(created).toBe(1);
});

test('youth requests normalize stale adult URL counts before creating candidates',async()=>{
    fetch.mockImplementation((url,options)=>url==='/api/users/me'?Promise.resolve(response({...member,birthDate:'2010-01-01'})):api(url,options));
    mount();await loaded();const body=JSON.parse(fetch.mock.calls.find(([url])=>url==='/api/smart-booking-candidates')[1].body);
    expect(body.audience).toEqual({adultCount:0,youthCount:2});
});

test('old single-group recovery URLs still restore their existing payment',async()=>{
    allocate(0);fetch.mockImplementation((url,options)=>{
        if(url==='/api/booking-groups/401') return Promise.resolve(response({id:401,status:'HOLDING',partySize:2,audience:{adultCount:2,youthCount:0},activeReservationId:501}));
        if(url==='/api/booking-groups/401/waiting-queues') return Promise.resolve(response({groupId:401,groupStatus:'HOLDING',activeReservationId:501,items:[],choices:[]}));
        return api(url,options);
    });
    mount(moviePath+'&group=401&reservation=501');await screen.findByRole('heading',{name:'좌석을 선점했습니다'});expect(created).toBe(0);
});


test('multiple held candidates show the preferred zone first without cancelling any candidate', async () => {
    plan.candidates.forEach((c,i) => { c.preferenceRank = 2-i; allocate(i); });
    vi.stubGlobal('fetch', vi.fn(api));
    render(<MemoryRouter initialEntries={[moviePath + '&smart=1']}><App /></MemoryRouter>);
    await loaded();
    const cards = candidates().getAllByRole('button');
    expect(cards[0].getAttribute('aria-label')).toContain('선호 좌석');
    expect(cards[0].getAttribute('aria-pressed')).toBe('true');
    expect(plan.candidates.every(c=>c.payment.reservation.status==='PENDING')).toBe(true);
});


test('the sidebar switches between movie recommendations and another selected show', async () => {
    plan.candidates.push({ ...plan.candidates[0], groupId: 410, kind: 'FAST', movieTitle: '다른 영화', theaterName: '다른 극장', showtimeId: 99,
        waiting: { ...plan.candidates[0].waiting, groupId:410, items:[{...plan.candidates[0].waiting.items[0],id:610,showtimeId:99}] } });
    mount(moviePath + '&smart=1'); await loaded();
    expect(candidates().getAllByRole('button')).toHaveLength(4);
    choose('다른 영화');
    expect(screen.getByRole('region',{name:'선택한 후보 상세'}).textContent).toContain('다른 영화');
    expect(screen.getByRole('region',{name:'선택한 후보 상세'}).textContent).toContain('다른 극장');
    expect(screen.getByTestId('url').textContent).toContain('candidate=410');
    expect(created).toBe(0);
});


test('a rejected new request still exposes existing smart waits for management', async () => {
    fetch.mockImplementation((url, options={}) => url==='/api/smart-booking-candidates' && options.method==='POST'
        ? Promise.resolve(response({message:'대기와 선점은 합쳐 최대 3개입니다.',code:'ACTIVE_BOOKING_LIMIT'},409)) : api(url,options));
    mount(); await screen.findByText('대기와 선점은 합쳐 최대 3개입니다.'); await loaded();
    expect(candidates().getAllByRole('button')).toHaveLength(3);
    choose('선호 좌석');
    expect(screen.getByTestId('url').textContent).toContain('candidate=403');
    fireEvent.click(await screen.findByRole('button',{name:'대기 취소'}));
    fireEvent.click(screen.getByRole('button',{name:'대기 취소 확정'}));
    await screen.findByRole('heading',{name:'대기 취소'});
    expect(candidates().getAllByRole('button')).toHaveLength(2);
});
