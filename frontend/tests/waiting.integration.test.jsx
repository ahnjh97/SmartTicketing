import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, expect, test, vi } from 'vitest';
import WaitingPanel from '../src/booking/WaitingPanel.jsx';
import { bookingApi } from '../src/api/booking.js';

vi.mock('../src/api/booking.js', () => ({ bookingApi: { waiting: vi.fn(), registerWaiting: vi.fn(), cancelGroup: vi.fn() } }));
const choices = [1,2].map(showtimeId => ({showtimeId,theaterName:`극장 ${showtimeId}`,screenName:'1관',startTime:'2026-10-03T15:00:00+09:00',endTime:'2026-10-03T17:00:00+09:00'}));
const initial = () => ({groupId:7,groupStatus:'ACTIVE',activeReservationId:null,items:[],choices});
let data, flow;
const item = (status,id=1) => ({id,showtimeId:id,queueNumber:17,aheadCount:2,status,...choices[id-1]});
beforeEach(() => {
    sessionStorage.clear(); vi.clearAllMocks(); data=initial();
    flow={user:{id:1},group:{id:7,status:'ACTIVE'},refresh:vi.fn(),busy:false};
    bookingApi.waiting.mockImplementation(async()=>data);
    bookingApi.registerWaiting.mockImplementation(async(group,ids)=>{data={...data,items:ids.map(id=>item('WAITING',id)),choices:choices.filter(c=>!ids.includes(c.showtimeId))};return data;});
    bookingApi.cancelGroup.mockImplementation(async()=>{data={...data,groupStatus:'CANCELLED',items:data.items.map(i=>({...i,status:'CANCELLED'})),choices:[]};return data;});
});
afterEach(()=>cleanup());

test('explicit multi-show selection is required and renders current rank from people ahead',async()=>{
    render(<WaitingPanel flow={flow}/>);
    await screen.findByRole('checkbox',{name:/극장 1/}); expect(bookingApi.registerWaiting).not.toHaveBeenCalled();
    expect(screen.getByRole('button',{name:'선택한 0개 회차에 대기 신청'}).disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox',{name:/극장 1/}));fireEvent.click(screen.getByRole('checkbox',{name:/극장 2/}));
    fireEvent.click(screen.getByRole('button',{name:'선택한 2개 회차에 대기 신청'}));
    await screen.findByRole('list',{name:'신청한 회차'});
    expect(bookingApi.registerWaiting).toHaveBeenCalledWith(7,[1,2],expect.any(String));
    expect(screen.getAllByText('3번')).toHaveLength(2); expect(screen.getAllByText('2건')).toHaveLength(2);
});
test('paused and holding are distinct and server allocation refreshes the shared reservation flow',async()=>{
    data={...initial(),groupStatus:'HOLDING',activeReservationId:99,items:[item('HOLDING'),item('PAUSED',2)],choices:[]};
    render(<WaitingPanel flow={flow}/>);
    await screen.findByText('다른 회차 선점으로 일시정지'); expect(screen.getByText('좌석 확보 후 결제 대기')).toBeTruthy();
    await waitFor(()=>expect(flow.refresh).toHaveBeenCalled());
    expect(screen.queryByRole('checkbox')).toBeNull();
});
test('uncertain registration retries with the same idempotency key without optimistic success',async()=>{
    bookingApi.registerWaiting.mockRejectedValueOnce(new TypeError('응답 유실'));
    render(<WaitingPanel flow={flow}/>); fireEvent.click(await screen.findByRole('checkbox',{name:/극장 1/}));
    fireEvent.click(screen.getByRole('button',{name:'선택한 1개 회차에 대기 신청'}));
    await screen.findByText('응답 유실'); expect(screen.queryByRole('list',{name:'신청한 회차'})).toBeNull();
    const heading = screen.getByRole('heading',{name:'대기 상태를 확인해주세요'});
    await waitFor(()=>expect(document.activeElement).toBe(heading));
    fireEvent.click(screen.getByRole('button',{name:'선택한 1개 회차에 대기 신청'})); await screen.findByRole('list',{name:'신청한 회차'});
    expect(bookingApi.registerWaiting.mock.calls[0][2]).toBe(bookingApi.registerWaiting.mock.calls[1][2]);
});
test('group cancellation is explicit and leaves terminal rows visible',async()=>{
    data={...initial(),items:[item('WAITING')],choices:[]};render(<WaitingPanel flow={flow}/>);
    fireEvent.click(await screen.findByRole('button',{name:'이 그룹 전체 취소'}));expect(bookingApi.cancelGroup).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button',{name:'전체 대기와 선점 취소 확정'}));await screen.findByText('이 관람 요청은 취소되었습니다.');
    expect(screen.getByText('취소됨')).toBeTruthy();expect(bookingApi.cancelGroup).toHaveBeenCalledTimes(1);
});
test('stale fetch is ignored after switching groups and cleaned-up requests are aborted',async()=>{
    let resolve; let signal;
    bookingApi.waiting.mockImplementationOnce((id,s)=>{signal=s;return new Promise(r=>{resolve=r;});});
    const view=render(<WaitingPanel flow={flow}/>);
    view.rerender(<WaitingPanel flow={{...flow,group:{id:8,status:'ACTIVE'}}}/>);
    await screen.findByRole('checkbox',{name:/극장 1/});
    await act(async()=>resolve({...initial(),items:[item('HOLDING')],activeReservationId:77,choices:[]}));
    expect(signal.aborted).toBe(true);expect(screen.queryByText('좌석 확보 후 결제 대기')).toBeNull();
});
test('expired opportunity has no rank while resumed waiting shows current rank',async()=>{
    data={...initial(),items:[item('EXPIRED'),item('WAITING',2)],choices:[]};render(<WaitingPanel flow={flow}/>);
    await screen.findByText('기회 종료');expect(screen.getByText('배정 대기')).toBeTruthy();expect(screen.getAllByText('3번')).toHaveLength(1);
    expect(screen.queryByRole('checkbox')).toBeNull();
});

test('issued number four shows first position and refreshes the current rank', async () => {
    data = {...initial(), items: [{...item('WAITING'), queueNumber: 4, aheadCount: 0}], choices: []};
    render(<WaitingPanel flow={flow}/>);
    await screen.findByText('1번');
    expect(screen.queryByText('4번')).toBeNull();
    expect(screen.getByText('2026.10.03 15:00')).toBeTruthy();
    data = {...data, items: [{...data.items[0], aheadCount: 2}]};
    fireEvent.focus(window);
    await screen.findByText('3번');
});
