import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import EmailVerification from '../src/components/EmailVerification.jsx';
import { authApi } from '../src/api/auth.js';

vi.mock('../src/api/auth.js', () => ({ authApi: { sendEmail: vi.fn(), verifyEmail: vi.fn() } }));
beforeEach(() => { vi.resetAllMocks(); authApi.sendEmail.mockResolvedValue({}); });
afterEach(cleanup);

test('normalizes the address, scopes verification and clears proof when email changes', async () => {
    const onChange = vi.fn();
    authApi.verifyEmail.mockResolvedValue({ verificationToken: 'single-use-proof' });
    render(<EmailVerification purpose="SIGNUP" onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'Member@Example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증번호 발송' }));
    await screen.findByLabelText('인증번호');
    expect(authApi.sendEmail).toHaveBeenCalledWith({ email: 'member@example.com', purpose: 'SIGNUP' });
    expect(screen.getByRole('button', { name: /초 후 재발송/ }).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('인증번호'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 확인' }));
    await screen.findByText(/이메일 인증이 완료되었습니다/);
    expect(authApi.verifyEmail).toHaveBeenCalledWith({ email: 'member@example.com', purpose: 'SIGNUP', code: '123456' });
    expect(onChange).toHaveBeenLastCalledWith({ email: 'member@example.com', verificationToken: 'single-use-proof' });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'other@example.com' } });
    expect(onChange).toHaveBeenLastCalledWith(null);
    expect(screen.queryByText(/이메일 인증이 완료되었습니다/)).toBeNull();
});

test('a failed code or malformed verification response cannot authorize the parent form', async () => {
    const onChange = vi.fn();
    authApi.verifyEmail.mockRejectedValueOnce(new Error('잘못된 인증번호')).mockResolvedValueOnce({});
    render(<EmailVerification purpose="RESET_PASSWORD" fixedEmail="member@example.com" onChange={onChange} />);
    fireEvent.click(screen.getByRole('button', { name: '인증번호 발송' }));
    await screen.findByLabelText('인증번호');
    fireEvent.change(screen.getByLabelText('인증번호'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: '인증 확인' }));
    await screen.findByText('잘못된 인증번호');
    fireEvent.click(screen.getByRole('button', { name: '인증 확인' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('인증 결과를 확인하지 못했습니다'));
    expect(onChange).not.toHaveBeenCalled();
});
