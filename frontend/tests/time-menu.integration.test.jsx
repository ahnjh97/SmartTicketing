import { useState } from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import TimeRangeMenu from '../src/booking/TimeRangeMenu.jsx';

afterEach(cleanup);
function Example({ date = '2026-10-01', now = Date.parse('2026-10-01T15:37:30+09:00'), initial = {} }) {
    const [value, setValue] = useState({ from: '', until: '', ...initial });
    return <><TimeRangeMenu date={date} now={now} {...value} update={v => setValue(p => ({ ...p, ...v }))} /><output>{JSON.stringify(value)}</output></>;
}
test('closed control is compact; only future half-hour choices appear and end follows start', () => {
    render(<Example />);
    expect(screen.queryByRole('region')).toBe(null);
    expect(screen.queryByRole('combobox')).toBe(null);
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    expect(screen.queryByRole('button', { name: '15:30', exact: true })).toBe(null);
    expect(screen.queryByRole('button', { name: '오전', exact: true })).toBe(null);
    fireEvent.click(screen.getByRole('button', { name: '16:00', exact: true }));
    const panel = screen.getByRole('region', { name: '종료시간 후보' });
    expect(within(panel).queryByRole('button', { name: '16:00', exact: true })).toBe(null);
    fireEvent.click(within(panel).getByRole('button', { name: '17:30', exact: true }));
    expect(screen.getByRole('status').textContent).toContain('"until":""');
    fireEvent.click(screen.getByRole('button', { name: '이 시간으로 적용' }));
    expect(screen.queryByRole('region')).toBe(null);
    expect(screen.getByRole('status').textContent).toContain('"until":"17:30"');
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '시작시간 선택' }));
});
test('night range explicitly chooses next-day slots and never permits an equal bound', () => {
    render(<Example initial={{ from: '23:30' }} />);
    fireEvent.click(screen.getByRole('button', { name: '종료시간 선택' }));
    expect(screen.queryByRole('button', { name: '23:30', exact: true })).toBe(null);
    fireEvent.click(screen.getByRole('button', { name: '00:30', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '이 시간으로 적용' }));
    expect(screen.getByRole('status').textContent).toContain('"until":"00:30"');
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '다시 선택' }));
    expect(screen.getByRole('button', { name: '이 시간으로 적용' }).disabled).toBe(true);
});
test('tomorrow offers midnight; escape closes without committing a range', () => {
    render(<Example date="2026-10-02" />);
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    expect(screen.getByRole('button', { name: '04:00', exact: true })).toBeTruthy();
    const panel = screen.getByRole('region', { name: '시작시간 후보' });
    expect(within(panel).getAllByRole('button').filter(b => /^\d\d:\d\d$/.test(b.textContent))).toHaveLength(48);
    fireEvent.keyDown(panel, { key: 'Escape' });
    expect(screen.queryByRole('region')).toBe(null);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '시작시간 선택' }));
});
test('after midnight remains selectable as the previous nights schedule', () => {
    render(<Example now={Date.parse('2026-10-01T23:45:00+09:00')} />);
    fireEvent.click(screen.getByRole('button', { name: '시작시간 선택' }));
    fireEvent.click(screen.getByRole('button', { name: '00:30', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '01:30', exact: true }));
    fireEvent.click(screen.getByRole('button', { name: '이 시간으로 적용' }));
    expect(screen.getByRole('status').textContent).toContain('"from":"00:30"');
    expect(screen.getByRole('status').textContent).toContain('"until":"01:30"');
});
