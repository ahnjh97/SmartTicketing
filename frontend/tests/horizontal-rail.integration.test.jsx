import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import HorizontalRail from '../src/components/HorizontalRail.jsx';
import GlassButton from '../src/components/GlassButton.jsx';

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
function mount({ width = 500, total = 1400, reduced = false } = {}) {
    const observers = [];
    vi.stubGlobal('ResizeObserver', class { constructor(fn) { observers.push(fn); } observe() {} disconnect() {} });
    vi.stubGlobal('matchMedia', () => ({ matches: reduced }));
    render(<HorizontalRail label="영화"><a href="/movies?movie=41">영화 41</a><a href="/movies?movie=42">영화 42</a></HorizontalRail>);
    const rail = screen.getByRole('region', { name: '영화' });
    Object.defineProperties(rail, { clientWidth: { configurable: true, value: width }, scrollWidth: { configurable: true, value: total } });
    rail.firstElementChild.getBoundingClientRect = () => ({ width: 200 });
    rail.scrollBy = vi.fn();
    act(() => observers.forEach(fn => fn()));
    return { rail, resize: () => act(() => observers.forEach(fn => fn())) };
}
test('overflow controls move by visible cards and update at both boundaries', () => {
    const { rail } = mount();
    const previous = rail.parentElement.querySelector('[aria-label="영화 이전"]');
    const next = screen.getByRole('button', { name: '영화 다음' });
    expect(previous.getAttribute('hidden')).toBe('');
    expect(next.getAttribute('hidden')).toBe(null);
    fireEvent.click(next);
    expect(rail.scrollBy).toHaveBeenCalledWith({ left: 400, behavior: 'smooth' });
    rail.scrollLeft = 900; fireEvent.scroll(rail);
    expect(previous.getAttribute('hidden')).toBe(null);
    expect(next.getAttribute('hidden')).toBe('');
    fireEvent.click(next);
    expect(rail.scrollBy).toHaveBeenCalledTimes(1);
    previous.focus();
    fireEvent.click(previous);
    expect(rail.scrollBy).toHaveBeenLastCalledWith({ left: -400, behavior: 'smooth' });
    rail.scrollLeft = 0; fireEvent.scroll(rail);
    expect(screen.queryByRole('button', { name: '영화 이전' })).toBe(null);
    expect(document.activeElement).toBe(rail);
});
test('fitting or empty lists hide arrows; resizing recalculates overflow', () => {
    const { rail, resize } = mount({ total: 300 });
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    Object.defineProperty(rail, 'clientWidth', { value: 200 }); resize();
    expect(screen.getByRole('button', { name: '영화 다음' }).hidden).toBe(false);
    Object.defineProperty(rail, 'scrollWidth', { value: 0 }); resize();
    expect(screen.queryByRole('button', { name: '영화 다음' })).toBe(null);
});
test('keyboard scrolling respects reduced motion and preserves real movie links', () => {
    const { rail } = mount({ reduced: true });
    fireEvent.keyDown(rail, { key: 'ArrowRight' });
    expect(rail.scrollBy).toHaveBeenCalledWith({ left: 400, behavior: 'instant' });
    expect(screen.getByRole('link', { name: '영화 41' }).getAttribute('href')).toBe('/movies?movie=41');
});
test('glass buttons do not submit forms by default and disabled actions cannot run', () => {
    const onClick = vi.fn();
    render(<GlassButton disabled onClick={onClick}>실행</GlassButton>);
    const button = screen.getByRole('button');
    expect(button.type).toBe('button');
    fireEvent.click(button); expect(onClick).not.toHaveBeenCalled();
});
