import { StrictMode } from "react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "../src/App.jsx";
import { userApi } from "../src/api/users.js";

// 지도 SDK 대신 저장 결과만 전달해 실제 인증·페이지 흐름을 검증한다.
vi.mock("../src/components/ResidencePreference.jsx", () => ({
    default: ({ user, onSaved }) => <button onClick={() => onSaved({
        ...user, birthDate: "2000-01-01", address: "서울",
        preferredTheaters: [{ theaterId: 1 }, { theaterId: 2 }, { theaterId: 3 }],
        preferredSeats: [{ position: "MIDDLE_MIDDLE", priority: 1 }],
    })}>선호정보 저장 테스트</button>,
}));

const completedUser = {
    id: 1, name: "테스터", nickname: "테스터", email: "test@example.com",
    birthDate: "2000-01-01", address: "서울", linkedProviders: ["LOCAL"],
    preferredTheaters: [{ theaterId: 1 }, { theaterId: 2 }, { theaterId: 3 }],
    preferredSeats: [{ position: "MIDDLE_MIDDLE", priority: 1 }],
};

function LocationProbe() {
    const location = useLocation();
    const navigate = useNavigate();
    return <>
        <div data-testid="location">{location.pathname}{location.search}{location.hash}</div>
        <button onClick={() => navigate(-1)}>테스트 뒤로가기</button>
    </>;
}

function mount(path, entries = [path]) {
    window.history.replaceState({}, "", path);
    return render(<StrictMode><MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <App /><LocationProbe />
    </MemoryRouter></StrictMode>);
}

async function at(path) {
    await waitFor(() => expect(screen.getByTestId("location").textContent).toBe(path));
}

beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal("fetch", vi.fn(async (url) => {
        if (url === "/api/auth/login") return new Response(JSON.stringify({ accessToken: "login-token" }));
        if (url === "/api/auth/signup") return new Response(JSON.stringify(completedUser));
        if (url === "/api/auth/logout") return new Response(null, { status: 204 });
        if (url === "/api/users/me") return new Response(JSON.stringify(completedUser));
        throw new Error(`Unexpected API: ${url}`);
    }));
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

test("header navigation changes paths and browser history without adding page bodies", async () => {
    mount("/login");
    await screen.findByLabelText("아이디");
    fireEvent.click(screen.getByRole("link", { name: "회원가입" }));
    await at("/signup");
    await screen.findByLabelText("이름");
    fireEvent.click(screen.getByRole("button", { name: "테스트 뒤로가기" }));
    await at("/login");
    fireEvent.click(screen.getByRole("link", { name: "영화" }));
    await at("/movies");
    expect(screen.getByRole("main").textContent).toBe("");
    fireEvent.click(screen.getByRole("link", { name: "극장" }));
    await at("/theaters");
    expect(screen.getByRole("main").textContent).toBe("");
    expect(fetch).not.toHaveBeenCalled();
});

test("guest direct access to a member page reaches login", async () => {
    mount("/preferences");
    await screen.findByLabelText("아이디");
    await at("/login");
    expect(fetch).not.toHaveBeenCalled();
});

test("signup posts the existing fields and returns to login with a success message", async () => {
    mount("/signup");
    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: "테스터" } });
    fireEvent.change(screen.getByLabelText("생년월일"), { target: { value: "2000-01-01" } });
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "tester" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "회원가입", exact: true }));
    await at("/login");
    expect((await screen.findByRole("status")).textContent).toContain("회원가입이 완료되었습니다.");
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({
        name: "테스터", birthDate: "2000-01-01", loginId: "tester", password: "password123",
    });
});

test("normal login retrieves the member and opens the existing profile", async () => {
    mount("/login");
    fireEvent.change(await screen.findByLabelText("아이디"), { target: { value: "tester" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인", exact: true }));
    await at("/profile");
    await screen.findByRole("heading", { name: "회원정보", exact: true });
    expect(fetch.mock.calls.map(([url]) => url)).toEqual(["/api/auth/login", "/api/users/me"]);
    expect(localStorage.getItem("accessToken")).toBe("login-token");
});

test("refresh restores a preferences deep link once even under StrictMode", async () => {
    localStorage.setItem("accessToken", "saved-token");
    mount("/preferences");
    await screen.findByRole("heading", { name: "회원정보 수정" });
    await at("/preferences");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe("Bearer saved-token");
});

test("OAuth removes URL credentials and progresses nickname, preferences, then profile", async () => {
    const kakaoUser = { id: 2, nickname: "카카오", linkedProviders: ["KAKAO"], email: null };
    fetch.mockImplementation(async (_, options) => new Response(JSON.stringify(
        options.method === "PATCH" ? { ...kakaoUser, nickname: JSON.parse(options.body).nickname } : kakaoUser,
    )));
    mount("/oauth2/callback#token=oauth-token");
    await screen.findByRole("heading", { name: "닉네임 설정" });
    await at("/setup/nickname");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe("Bearer oauth-token");
    fireEvent.change(screen.getByLabelText("닉네임"), { target: { value: "영화팬" } });
    fireEvent.click(screen.getByRole("button", { name: "닉네임 저장" }));
    await screen.findByRole("heading", { name: "선호 정보 설정" });
    await at("/setup/preferences");
    fireEvent.click(screen.getByRole("button", { name: "선호정보 저장 테스트" }));
    await screen.findByRole("heading", { name: "회원정보", exact: true });
    await at("/profile");
    expect(localStorage.getItem("kakaoProfileSetupDone:2")).toBe("true");
});

test("401 during a member API call clears authentication and redirects the mounted page", async () => {
    localStorage.setItem("accessToken", "expired-token");
    mount("/profile");
    await screen.findByRole("heading", { name: "회원정보", exact: true });
    fetch.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await act(async () => {
        await expect(userApi.me()).rejects.toMatchObject({ status: 401 });
    });
    await at("/login");
    expect(localStorage.getItem("accessToken")).toBe(null);
    expect((await screen.findByRole("alert")).textContent).toContain("로그인이 만료되었습니다.");
});

test("logout clears the session and leaves member routes", async () => {
    localStorage.setItem("accessToken", "saved-token");
    mount("/profile");
    await screen.findByRole("heading", { name: "회원정보", exact: true });
    fireEvent.click(screen.getAllByRole("button", { name: "로그아웃" })[0]);
    await at("/login");
    expect(localStorage.getItem("accessToken")).toBe(null);
    expect(fetch.mock.calls[1][0]).toBe("/api/auth/logout");
});
