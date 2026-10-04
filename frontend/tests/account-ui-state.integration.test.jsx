import { StrictMode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import CommonHeader from '../src/components/CommonHeader.jsx';
import NotificationsPage from '../src/pages/NotificationsPage.jsx';
import TicketsPage from '../src/pages/TicketsPage.jsx';
import { notificationApi } from '../src/api/notifications.js';
import { ticketApi } from '../src/api/tickets.js';

vi.mock('../src/api/notifications.js', () => ({ notificationApi: { list: vi.fn() } }));
vi.mock('../src/api/tickets.js', () => ({ ticketApi: { mine: vi.fn(), verifyStatus: vi.fn() } }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

test('account changes close the panel and discard the previous account response', async () => {
    let finishOld;
    notificationApi.list.mockImplementation(unread => unread ? Promise.resolve([]) : new Promise(resolve => { finishOld = resolve; }));
    const header = user => <MemoryRouter><CommonHeader user={user} onLogout={() => {}} /></MemoryRouter>;
    const view = render(header({ id: 1 }));
    fireEvent.click(screen.getByRole('button', {name:'알림 열기'}));
    view.rerender(header({ id: 2 }));
    expect(screen.queryByRole('button', {name:'알림 닫기'})).toBeNull();
    await act(async () => finishOld([{id:1,message:'이전 계정 알림',read:false}]));
    notificationApi.list.mockResolvedValue([]);
    fireEvent.click(screen.getByRole('button', {name:'알림 열기'}));
    await screen.findByText('새로운 알림이 없습니다.');
    expect(screen.queryByText('이전 계정 알림')).toBeNull();
});

test('notification effect ignores an older response after cleanup', async () => {
    const responses = [];
    notificationApi.list.mockImplementation(() => new Promise(resolve => responses.push(resolve)));
    render(<StrictMode><NotificationsPage /></StrictMode>);
    expect(responses).toHaveLength(2);
    await act(async () => responses[1]([{id:2,message:'현재 알림',read:true}]));
    await act(async () => responses[0]([{id:1,message:'이전 알림',read:true}]));
    expect(screen.getByText('현재 알림')).toBeTruthy();
    expect(screen.queryByText('이전 알림')).toBeNull();
});

test('a valid ticket does not inherit the previous used ticket verification state', async () => {
    ticketApi.mine.mockResolvedValue([
        {ticketId:1,ticketNumber:'USED-1',movieTitle:'사용한 영화',status:'USED',startTime:'2026-10-05T12:00:00+09:00'},
        {ticketId:2,ticketNumber:'VALID-2',movieTitle:'새 영화',status:'VALID',startTime:'2026-10-05T14:00:00+09:00'},
    ]);
    ticketApi.verifyStatus.mockImplementation(() => new Promise(() => {}));
    render(<TicketsPage />);
    fireEvent.click(await screen.findByRole('button', {name:/사용한 영화/}));
    expect(screen.getByText('USED')).toBeTruthy();
    fireEvent.click(screen.getByRole('presentation'));
    fireEvent.click(screen.getByRole('button', {name:/새 영화/}));
    expect(screen.queryByText('USED')).toBeNull();
    expect(screen.queryByText('처리 중입니다...')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', {name:'티켓 상세 닫기'}));
    fireEvent.keyDown(document, {key:'Escape'});
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.body.style.overflow).not.toBe('hidden');
});
