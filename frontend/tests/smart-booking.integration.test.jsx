import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import App from '../src/App.jsx';
import { seoulDate } from '../src/booking/state.js';

const day = seoulDate(new Date(Date.now()+86400000));
const member = { id: 1, nickname: '관객', birthDate: '1990-01-01', address: '서울', preferredTheaters: [1,2,3].map(theaterId=>({theaterId})), preferredSeats: [{position:'MIDDLE_MIDDLE',priority:1}] };
const movie = { id:41, movieId:41, title:'서울의 밤', rating:'ALL', backdropUrl:'/wide.jpg' };
const show = { id:91,movieId:41,theaterId:71,screenName:'1관',startTime:`${day}T23:00:00+09:00`,endTime:`${day}T23:59:00+09:00`,layoutComplete:true,availableSeats:4,totalSeats:4,maxContiguousSeats:4 };
const reservation = { id:501,groupId:401,status:'PENDING',movieTitle:movie.title,theaterName:'서울 극장',screenName:'1관',startTime:show.startTime,endTime:show.endTime,
    seatIds:[1,2],seatLabels:['A1','A2'],totalAmount:20000,expiresAt:`${day}T09:05:00+09:00`,serverTime:`${day}T09:00:00+09:00` };
let group, saved, failure, lost, attempts, paid, pending;
const response = (body,status=200) => new Response(JSON.stringify(body),{status});
async function api(url,options={}) {
    if(url==='/api/booking-groups/401/waiting-queues') return response({groupId:401,groupStatus:'ACTIVE',activeReservationId:null,items:[],choices:[]});
    if(url==='/api/users/me') return response(member);
    if(url.startsWith('/api/movies?')) return response({items:[movie]});
    if(url==='/api/movies/41') return response(movie);
    if(url.startsWith('/api/theaters?')) return response({items:[]});
    if(url==='/api/theaters/71') return response({id:71,name:'서울 극장'});
    if(url.startsWith('/api/theaters/71/movies')) return response({items:[movie]});
    if(url.startsWith('/api/showtimes?')) return response({items:[show]});
    if(url==='/api/booking-groups') { if(pending) return pending; group={id:401,...JSON.parse(options.body),seatPreferences:['MIDDLE_MIDDLE']}; return response(group,201); }
    if(url==='/api/booking-groups/401') return response({...group,activeReservationId:saved?.id});
    if(url==='/api/booking-groups/401/smart-hold') { attempts++;
        if(lost && attempts===1) throw new TypeError('네트워크 연결 끊김');
        if(failure) return response({code:failure,message:'좌석을 확보할 수 없습니다.'},409);
        saved={...reservation}; return response(saved,201);
    }
    if(url==='/api/reservations/501/payment') return response({reservation:saved,status:paid?'SUCCESS':null,ticket:paid?{ticketId:701,ticketNumber:'ST-SMART'}:null});
    if(url==='/api/reservations/501/mock-payments') { paid=true;saved={...saved,status:'CONFIRMED'};return response({reservation:saved},201); }
    throw new Error(`Unexpected ${url}`);
}
function Probe() { const location=useLocation(); const navigate=useNavigate(); return <><output data-testid="url">{location.pathname}{location.search}</output><button onClick={()=>navigate('/movies?movie=41')}>조건 화면으로 이동</button></>; }
const moviePath=`/movies?movie=41&date=${day}&party=2&from=22:00&until=02:00&entry=MOVIE_SMART`;
const theaterPath=`/theaters?theater=71&movie=41&showtime=91&date=${day}&entry=THEATER_SMART`;
function mount(path=moviePath) { return render(<MemoryRouter initialEntries={[path]}><App/><Probe/></MemoryRouter>); }
beforeEach(()=>{ localStorage.clear();sessionStorage.clear();localStorage.setItem('accessToken','test'); group=null;saved=null;failure=null;lost=false;attempts=0;paid=false;pending=null;vi.stubGlobal('fetch',vi.fn(api)); });
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
async function confirm() { fireEvent.click(await screen.findByLabelText(/동반 관객 모두/));
    await waitFor(()=>expect(screen.getByRole('button',{name:'자동으로 찾아 5분 선점 →'}).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'자동으로 찾아 5분 선점 →'})); }

test('movie smart sends range and audience without seat IDs, restores and uses shared mock payment',async()=>{
    mount(); await confirm(); await screen.findByRole('heading',{name:'좌석을 선점했습니다'});
    const body=JSON.parse(fetch.mock.calls.find(([url])=>url==='/api/booking-groups')[1].body);
    expect(body).toMatchObject({entryPoint:'MOVIE_SMART',movieId:41,partySize:2,startTimeFrom:'22:00',startTimeTo:'02:00'});
    expect(body.selectedShowtimeId).toBeUndefined();
    const hold=fetch.mock.calls.find(([url])=>url.endsWith('/smart-hold')); expect(hold[1].body).toBeUndefined();
    expect(fetch.mock.calls.some(([url])=>url.includes('/seats')||url.includes('/manual-hold'))).toBe(false);
    const url=screen.getByTestId('url').textContent; cleanup();mount(url);
    await screen.findByRole('timer');fireEvent.click(await screen.findByRole('button',{name:'20,000원 모의결제'}));
    await screen.findByRole('heading',{name:'예매가 완료되었습니다'}); expect(screen.getByText('ST-SMART')).toBeTruthy();
});
test('theater smart sends only selected showtime and collects party on this screen',async()=>{
    mount(theaterPath); fireEvent.change(await screen.findByLabelText('성인 인원'),{target:{value:'2'}});await confirm();
    await screen.findByRole('heading',{name:'좌석을 선점했습니다'});
    const body=JSON.parse(fetch.mock.calls.find(([url])=>url==='/api/booking-groups')[1].body);
    expect(body).toMatchObject({entryPoint:'THEATER_SMART',selectedShowtimeId:91,partySize:2});expect(body.startTimeFrom).toBeUndefined();
});
test.each(['SOLD_OUT','NO_CONTIGUOUS_SEATS','LAYOUT_UNVERIFIED'])('%s offers explicit alternatives and honest waiting notice',async code=>{
    failure=code;mount();await confirm();await screen.findByRole('heading',{name:'이번에는 자리를 확보하지 못했어요'});
    fireEvent.click(screen.getByRole('button',{name:'예비번호·대기 안내'}));
    expect(screen.getByText('신청 전에는 대기 등록이나 예비번호 발급이 이루어지지 않습니다.')).toBeTruthy();
    expect(fetch.mock.calls.some(([url,options])=>/waiting|queues/.test(url) && options.method==='POST')).toBe(false);
    expect(screen.getByRole('link',{name:/극장·회차 직접 선택/})).toBeTruthy();
});
test('network retry preserves smart request key and never optimistically confirms seats',async()=>{
    lost=true;mount();await confirm();await screen.findByText('네트워크 연결 끊김');
    expect(screen.queryByRole('heading',{name:'좌석을 선점했습니다'})).toBeNull();
    await waitFor(()=>expect(screen.getByRole('button',{name:'자동으로 찾아 5분 선점 →'}).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button',{name:'자동으로 찾아 5분 선점 →'}));await screen.findByRole('heading',{name:'좌석을 선점했습니다'});
    const holds=fetch.mock.calls.filter(([url])=>url.endsWith('/smart-hold'));expect(holds).toHaveLength(2);
    expect(holds[0][1].headers['Idempotency-Key']).toBe(holds[1][1].headers['Idempotency-Key']);
});
test('leaving while creating a group never auto-holds the stale selection',async()=>{
    let resolve;pending=new Promise(r=>{resolve=r;});mount();await confirm();
    await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url==='/api/booking-groups')).toBe(true));
    fireEvent.click(screen.getByRole('button',{name:'조건 화면으로 이동'}));
    await act(async()=>resolve(response({id:401},201)));
    expect(fetch.mock.calls.some(([url])=>url.endsWith('/smart-hold'))).toBe(false);
});
test('a committed hold with lost response restores its reservation URL before payment clears the slot',async()=>{
    fetch.mockImplementation(async(url,options)=>{
        if(url.endsWith('/smart-hold')) { saved={...reservation};throw new TypeError('응답 유실'); }
        if(url==='/api/booking-groups/401' && paid) return response({...group,activeReservationId:null});
        return api(url,options);
    });
    mount();await confirm();await screen.findByRole('heading',{name:'좌석을 선점했습니다'});
    expect(screen.getByTestId('url').textContent).toContain('reservation=501');
    fireEvent.click(await screen.findByRole('button',{name:'20,000원 모의결제'}));await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
    const path=screen.getByTestId('url').textContent;cleanup();mount(path);
    await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
});
test('a new waiting hold replaces an old reservation URL and survives completed payment',async()=>{
    group={id:401,partySize:2,audience:{adultCount:2,youthCount:0,companionsEligible:true,guardianAccompanying:false}};
    saved={...reservation,id:502};
    fetch.mockImplementation(async(url,options)=>{
        if(url==='/api/booking-groups/401') return response({...group,activeReservationId:paid?null:502});
        if(url==='/api/reservations/502/payment') return response({reservation:saved,status:paid?'SUCCESS':null,ticket:null});
        if(url==='/api/reservations/502/mock-payments') {paid=true;saved={...saved,status:'CONFIRMED'};return response({reservation:saved},201);}
        return api(url,options);
    });
    mount(`${moviePath}&group=401&reservation=501`);
    await screen.findByRole('heading',{name:'좌석을 선점했습니다'});expect(screen.getByTestId('url').textContent).toContain('reservation=502');
    fireEvent.click(screen.getByRole('button',{name:'20,000원 모의결제'}));await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
    const path=screen.getByTestId('url').textContent;cleanup();mount(path);await screen.findByRole('heading',{name:'예매가 완료되었습니다'});
});

test('no seat availability still allows smart failure guidance; mismatched audience cannot submit',async()=>{
    mount();fireEvent.change(await screen.findByLabelText('성인 인원'),{target:{value:'1'}});
    fireEvent.click(screen.getByLabelText(/동반 관객 모두/));
    expect(screen.getByRole('button',{name:'자동으로 찾아 5분 선점 →'}).disabled).toBe(true);
    expect(fetch.mock.calls.some(([url])=>url==='/api/booking-groups')).toBe(false);
});
