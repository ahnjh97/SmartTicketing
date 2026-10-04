import { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import TimeRangeMenu from '../src/booking/TimeRangeMenu.jsx';
afterEach(cleanup);
function Example({ date='2026-10-01', now=Date.parse('2026-10-01T15:37:30+09:00'), runningTime=130, initial={} }) {
    const [value,setValue]=useState({from:'',until:'',...initial});
    return <><TimeRangeMenu date={date} now={now} runningTime={runningTime} {...value} update={setValue}/><output>{JSON.stringify(value)}</output></>;
}
const open=()=>fireEvent.click(screen.getByRole('button',{name:'영화 최소 시작시간 선택'}));
const change=(name,value)=>name.endsWith(' 분') ? fireEvent.click(screen.getByRole('button',{name:name.replace(' 분', ' ' + String(value).padStart(2,'0') + '분')})) : fireEvent.change(screen.getByRole('combobox',{name}),{target:{value:String(value)}});
const apply=()=>fireEvent.click(screen.getByRole('button',{name:'이 시간으로 적용'}));

test('schedule limits exclude early hours and include rounded last-start boundary',()=>{
    render(<Example date="2026-10-02"/>);open();
    const hours=screen.getByRole('combobox',{name:'최소 시작시간 시'});
    expect(within(hours).queryByRole('option',{name:'07시'})).toBeNull();
    expect(within(hours).getByRole('option',{name:'08시'})).toBeTruthy();
    expect(within(hours).queryByRole('option',{name:'01시'})).toBeNull();
    change('최소 시작시간 시',24);change('최소 시작시간 분',30);
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('25');
    expect(screen.getByRole('button',{name:'최대 시작시간 00분'}).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button',{name:'최대 시작시간 30분'}).disabled).toBe(true);
    apply();expect(screen.getByRole('status').textContent).toContain('"until":"01:00"');
});
test('minimum defaults maximum to plus 30 minutes and allows manual changes',()=>{
    render(<Example/>);open();
    expect(within(screen.getByRole('combobox',{name:'최소 시작시간 시'})).queryByRole('option',{name:'15시'})).toBeNull();
    change('최소 시작시간 시',16);
    expect(screen.getByRole('button',{name:'최대 시작시간 30분'}).getAttribute('aria-pressed')).toBe('true');
    change('최대 시작시간 시',18);change('최대 시작시간 분',0);
    expect(screen.getByRole('status').textContent).toContain('"until":""');
    apply();expect(screen.getByRole('status').textContent).toContain('"until":"18:00"');
    expect(document.activeElement).toBe(screen.getByRole('button',{name:'영화 최소 시작시간 선택'}));
});
test('changing minimum resets maximum, including midnight rollover',()=>{
    render(<Example initial={{from:'16:00',until:'20:00'}}/>);open();
    change('최소 시작시간 시',23);change('최소 시작시간 분',30);
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('24');
    expect(screen.getByRole('button',{name:'최대 시작시간 00분'}).getAttribute('aria-pressed')).toBe('true');
    apply();expect(screen.getByRole('status').textContent).toContain('"until":"00:00"');
});
test('runtime changes the last possible start and no future options disables apply',()=>{
    render(<Example runningTime={200} now={Date.parse('2026-10-02T01:00:00+09:00')}/>);open();
    expect(screen.getByRole('button',{name:'이 시간으로 적용'}).disabled).toBe(true);
    expect(screen.getByText('선택 가능한 시간이 없습니다. 날짜를 다시 선택해주세요.')).toBeTruthy();
});
test('last start exactly on a half-hour still has a valid default upper bound',()=>{
    render(<Example date="2026-10-02" runningTime={140}/>);open();
    change('최소 시작시간 시',24);change('최소 시작시간 분',30);
    expect(screen.getByRole('button',{name:'이 시간으로 적용'}).disabled).toBe(false);
    apply();expect(screen.getByRole('status').textContent).toContain('"until":"01:00"');
});
test('reset and escape discard drafts without changing committed values',()=>{
    render(<Example initial={{from:'16:00',until:'18:00'}}/>);open();
    fireEvent.click(screen.getByRole('button',{name:'다시 선택'}));
    expect(screen.getByRole('button',{name:'이 시간으로 적용'}).disabled).toBe(true);
    fireEvent.keyDown(screen.getByRole('dialog'),{key:'Escape'});
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('status').textContent).toContain('"until":"18:00"');
});

test('hour buttons move exactly one hour, preserve minutes, and stop at schedule bounds',()=>{
    render(<Example date="2026-10-02" initial={{from:'08:30',until:'09:00'}}/>);open();
    expect(screen.getByRole('button',{name:'최소 시작시간 한 시간 이전'}).disabled).toBe(true);
    fireEvent.click(screen.getByRole('button',{name:'최소 시작시간 한 시간 이후'}));
    expect(screen.getByRole('combobox',{name:'최소 시작시간 시'}).value).toBe('9');
    expect(screen.getByRole('button',{name:'최소 시작시간 30분'}).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('10');
    fireEvent.click(screen.getByRole('button',{name:'최대 시작시간 한 시간 이후'}));
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('11');
    fireEvent.click(screen.getByRole('button',{name:'최소 시작시간 한 시간 이전'}));
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('11');
    change('최소 시작시간 시',24);
    expect(screen.getByRole('button',{name:'최소 시작시간 한 시간 이후'}).disabled).toBe(true);
});

test('preserves a chosen maximum until minimum meets or passes it',()=>{
    render(<Example initial={{from:'16:00',until:'20:00'}}/>);open();
    change('최소 시작시간 시',18);
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('20');
    expect(screen.getByRole('button',{name:'최대 시작시간 00분'}).getAttribute('aria-pressed')).toBe('true');
    change('최소 시작시간 시',20);
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('20');
    expect(screen.getByRole('button',{name:'최대 시작시간 30분'}).getAttribute('aria-pressed')).toBe('true');
    change('최소 시작시간 시',21);
    expect(screen.getByRole('combobox',{name:'최대 시작시간 시'}).value).toBe('21');
    expect(screen.getByRole('button',{name:'최대 시작시간 30분'}).getAttribute('aria-pressed')).toBe('true');
});
