import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test } from 'vitest';
import { MemoryRouter, useSearchParams } from 'react-router-dom';
import useMovieTimeDefaults from '../src/booking/useMovieTimeDefaults.js';
import TimeRangeMenu from '../src/booking/TimeRangeMenu.jsx';

const now = Date.parse('2026-10-07T15:37:00+09:00');
function Example({ clock = now }) {
    const [params, setParams] = useSearchParams();
    const date = params.get('date') || '2026-10-07';
    const range = useMovieTimeDefaults({ enabled: true, userId: 1, movieId: 1, date, now: clock, runningTime: 120,
        schedule: { earliestStartTime: `${date}T08:15:00+09:00`, latestStartTime: `${date}T23:15:00+09:00` }, params });
    return <><TimeRangeMenu date={date} now={clock} {...range} runningTime={120} update={value => setParams({ date, ...value })}/>
        <button onClick={() => setParams({date: '2026-10-08'})}>내일</button>
        <button onClick={() => setParams({date: '2026-10-07'})}>오늘</button></>;
}
const view = (path = '/', clock = now) => <MemoryRouter initialEntries={[path]}><Example clock={clock}/></MemoryRouter>;
const start = () => screen.getByRole('button', { name: '영화 최소 시작시간 선택' });
const end = () => screen.getByRole('button', { name: '영화 최대 시작시간 선택' });
beforeEach(() => sessionStorage.clear());
afterEach(cleanup);

test('initial range is populated, past slots are unavailable and future dates start at the first show boundary', () => {
    render(view());
    expect(start().textContent).toBe('16:00');
    expect(end().textContent).toBe('23:30');
    fireEvent.click(start());
    expect(within(screen.getByRole('combobox', {name:'최소 시작시간 시'})).queryByRole('option', {name:'15시'})).toBeNull();
    fireEvent.click(screen.getByRole('button', {name:'시간 선택 닫기'}));
    fireEvent.click(screen.getByRole('button', {name:'내일'}));
    expect(start().textContent).toBe('08:00');
});

test('edited times survive date changes and re-entry without resetting to defaults', async () => {
    render(view());
    fireEvent.click(start());
    fireEvent.change(screen.getByRole('combobox', {name:'최소 시작시간 시'}), {target:{value:'18'}});
    fireEvent.change(screen.getByRole('combobox', {name:'최대 시작시간 시'}), {target:{value:'21'}});
    fireEvent.click(screen.getByRole('button', {name:'이 시간으로 적용'}));
    expect(start().textContent).toBe('18:00');
    expect(end().textContent).toBe('21:30');
    fireEvent.click(screen.getByRole('button', {name:'내일'}));
    expect(start().textContent).toBe('08:00');
    fireEvent.click(screen.getByRole('button', {name:'오늘'}));
    expect(start().textContent).toBe('18:00');
    cleanup(); render(view());
    await waitFor(() => expect(start().textContent).toBe('18:00'));
    expect(end().textContent).toBe('21:30');
});

test('elapsed URL selections advance safely while a future maximum is preserved', () => {
    const { rerender } = render(view('/?from=16:00&until=21:00'));
    expect(start().textContent).toBe('16:00');
    rerender(view('/?from=16:00&until=21:00', Date.parse('2026-10-07T16:01:00+09:00')));
    expect(start().textContent).toBe('16:30');
    expect(end().textContent).toBe('21:00');
});
