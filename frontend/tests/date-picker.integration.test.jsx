import { useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import DatePicker from '../src/components/DatePicker.jsx';

afterEach(cleanup);
function Example() {
    const [value, setValue] = useState('');
    return <><label htmlFor="birth">생년월일</label><DatePicker id="birth" value={value} onChange={e => setValue(e.target.value)} max="2026-10-13" required /></>;
}
test('selects a leap day and restores focus to the calendar trigger', () => {
    render(<Example />);
    const trigger = screen.getByRole('button', { name: '생년월일 달력 열기' });
    fireEvent.click(trigger);
    fireEvent.change(screen.getByLabelText('연도'), { target: { value: '2000' } });
    fireEvent.change(screen.getByLabelText('월'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: '2000년 2월 29일' }));
    expect(screen.getByLabelText('생년월일').value).toBe('2000-02-29');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
});
test('disables future days and navigation beyond the birth-date range', () => {
    render(<Example />);
    fireEvent.click(screen.getByRole('button', { name: '생년월일 달력 열기' }));
    expect(screen.getByRole('button', { name: '2026년 10월 14일' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: '다음 달' }).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('연도'), { target: { value: '1900' } });
    fireEvent.change(screen.getByLabelText('월'), { target: { value: '1' } });
    expect(screen.getByRole('button', { name: '이전 달' }).disabled).toBe(true);
});
test('supports typing dates and Escape without changing the entered value', () => {
    render(<Example />);
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1995-05-20' } });
    const trigger = screen.getByRole('button', { name: '생년월일 달력 열기' });
    fireEvent.click(trigger);
    expect(screen.getByRole('button', { name: '1995년 5월 20일' }).getAttribute('aria-pressed')).toBe('true');
    fireEvent.keyDown(screen.getByLabelText('연도'), { key: 'Escape' });
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByLabelText('생년월일').value).toBe('1995-05-20');
});
