import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, expect, test, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import AuthProvider from "../src/auth/AuthProvider.jsx";
import useAuth from "../src/hooks/useAuth.js";
import ProfilePage from "../src/pages/ProfilePage.jsx";
import LoginPage from "../src/pages/LoginPage.jsx";

function Profile() {
    const { user } = useAuth();
    return user ? <ProfilePage /> : null;
}

beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem("accessToken", "member-token");
    HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ id: 1, name: "회원" }))));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

async function openDialog() {
    render(<MemoryRouter initialEntries={["/profile"]}><AuthProvider><Routes>
        <Route path="/profile" element={<Profile />} />
        <Route path="/login" element={<LoginPage />} />
    </Routes></AuthProvider></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "회원 탈퇴" }));
    return screen.getByRole("dialog");
}

test("cancel sends no withdrawal; confirmation clears authentication and shows completion", async () => {
    await openDialog();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(fetch.mock.calls.filter(([, options]) => options.method === "DELETE")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "회원 탈퇴" }));
    fetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
    fireEvent.click(screen.getByRole("button", { name: "탈퇴하기" }));
    await screen.findByText("회원 탈퇴가 완료되었습니다.");
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(fetch.mock.calls.filter(([url, options]) => url === "/api/users/me" && options.method === "DELETE")).toHaveLength(1);
});

test("pending blocks duplicate submission; failure keeps the account and permits retry", async () => {
    await openDialog();
    let finish;
    fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const confirm = screen.getByRole("button", { name: "탈퇴하기" });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(confirm.disabled).toBe(true);
    expect(screen.getByRole("button", { name: "취소" }).disabled).toBe(true);
    finish(new Response(JSON.stringify({ message: "처리에 실패했습니다." }), { status: 500 }));
    expect((await screen.findByRole("alert")).textContent).toBe("처리에 실패했습니다.");
    expect(localStorage.getItem("accessToken")).toBe("member-token");
    await waitFor(() => expect(confirm.disabled).toBe(false));
    expect(fetch.mock.calls.filter(([, options]) => options.method === "DELETE")).toHaveLength(1);
    fetch.mockResolvedValueOnce(new Response(null, { status: 204 }));
    fireEvent.click(confirm);
    await screen.findByText("회원 탈퇴가 완료되었습니다.");
});
