import { StrictMode } from "react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
    sessionStorage.clear();
    vi.stubGlobal("fetch", vi.fn(async (url) => {
        if (url.startsWith("/api/movies?") || url.startsWith("/api/theaters?")) return new Response(JSON.stringify({ items: [], page: 0, size: 20, totalElements: 0 }));
        if (url === "/api/auth/login") return new Response(JSON.stringify({ accessToken: "login-token" }));
        if (url.startsWith("/api/auth/check-login-id?")) return new Response(JSON.stringify({ available: true }));
        if (url === "/api/auth/signup") return new Response(JSON.stringify(completedUser));
        if (url === "/api/auth/logout") return new Response(null, { status: 204 });
        if (url === "/api/users/me") return new Response(JSON.stringify(completedUser));
        if (url === "/api/tickets") return new Response('[]');
        throw new Error(`Unexpected API: ${url}`);
    }));
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

test("header navigation changes paths and browser history with public booking pages", async () => {
    mount("/login");
    await screen.findByLabelText("아이디");
    const movies = screen.getByRole("link", { name: "영화" });
    const theaters = screen.getByRole("link", { name: "극장" });
    expect(movies.getAttribute("aria-current")).toBe(null);
    expect(theaters.getAttribute("aria-current")).toBe(null);
    fireEvent.click(screen.getByRole("link", { name: "회원가입" }));
    await at("/signup");
    await screen.findByLabelText("이름");
    expect(movies.getAttribute("aria-current")).toBe(null);
    expect(theaters.getAttribute("aria-current")).toBe(null);
    fireEvent.click(screen.getByRole("button", { name: "테스트 뒤로가기" }));
    await at("/login");
    fireEvent.click(screen.getByRole("link", { name: "영화" }));
    await at("/movies");
    expect(movies.getAttribute("aria-current")).toBe("page");
    expect(theaters.getAttribute("aria-current")).toBe(null);
    expect(screen.getByRole("heading", { name: "영화별 예매" })).toBeTruthy();
    fireEvent.click(screen.getByRole("link", { name: "극장" }));
    await at("/theaters");
    expect(movies.getAttribute("aria-current")).toBe(null);
    expect(theaters.getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("heading", { name: "극장별 예매" })).toBeTruthy();
    fireEvent.click(screen.getByRole("link", { name: "SmartTicketing" }));
    await at("/");
    expect(movies.getAttribute("aria-current")).toBe("page");
    expect(theaters.getAttribute("aria-current")).toBe(null);
    expect(screen.queryByRole("link", { name: "영화 목록에서 예매 시작하기 →" })).toBe(null);
});

test("guest direct access to a member page reaches login", async () => {
    mount("/preferences");
    await screen.findByLabelText("아이디");
    await at("/login");
    expect(fetch).not.toHaveBeenCalled();
});

test('all public pages reuse the home header without route-specific appearance', async () => {
    for (const path of ['/', '/movies', '/theaters', '/login', '/signup']) {
        mount(path);
        await at(path);
        const header = screen.getByRole('banner');
        expect(screen.getAllByRole('banner')).toHaveLength(1);
        expect(header.className).toBe('common-header');
        expect(within(header).getAllByRole('link').map(link => link.textContent)).toEqual(['SmartTicketing', '영화', '극장', '로그인', '회원가입']);
        cleanup();
    }
});

test('member header keeps tickets immediately before logout on home and booking pages', async () => {
    localStorage.setItem('accessToken', 'header-test-token');
    for (const path of ['/', '/movies', '/theaters', '/profile', '/tickets']) {
        mount(path);
        await within(screen.getByRole('banner')).findByRole('button', { name: '로그아웃' });
        const header = screen.getByRole('banner');
        expect(header.className).toBe('common-header');
        const account = within(header).getByRole('navigation', { name: '회원 메뉴' });
        expect([...account.children].map(item => item.textContent)).toEqual(['내 티켓', '로그아웃', '마이페이지']);
        cleanup();
    }
});

test("social signup retains its direct route and loads provider information", async () => {
    fetch.mockImplementation(async (url) => new Response(JSON.stringify(
        url === "/api/auth/social-signup-info"
            ? { provider: "GOOGLE", name: "소셜 테스터", email: "social@example.com" }
            : { available: true },
    )));
    mount("/signup/social?social=GOOGLE");
    await screen.findByRole("heading", { name: "소셜 회원가입" });
    await screen.findByText("사용 가능한 이메일입니다.");
    await at("/signup/social?social=GOOGLE");
    expect(screen.getByLabelText("이메일").value).toBe("social@example.com");
    expect(screen.getByLabelText("이메일").readOnly).toBe(true);
    expect(screen.queryByLabelText("닉네임")).toBe(null);
});

test("tickets load the existing API for members and redirect guests to login", async () => {
    mount("/tickets");
    await screen.findByLabelText("아이디");
    await at("/login");
    expect(screen.queryByRole("link", { name: "내 티켓" })).toBe(null);
    cleanup();
    localStorage.setItem("accessToken", "saved-token");
    mount("/tickets");
    await screen.findByRole("link", { name: "내 티켓" });
    await at("/tickets");
    expect(await screen.findByText('발급된 티켓이 없습니다.')).toBeTruthy();
});

test("signup accepts a non-email login ID and opens preference setup with its issued token", async () => {
    const originalWindow = window;
    const assign = vi.fn();
    const testWindow = Object.create(originalWindow);
    Object.defineProperty(testWindow, "location", { value: {
        get href() { return originalWindow.location.href; }, assign,
    } });
    vi.stubGlobal("window", testWindow);
    fetch.mockImplementation(async (url) => new Response(JSON.stringify(
        url === "/api/auth/signup" ? { accessToken: "signup-token" } : { available: true },
    )));
    mount("/signup");
    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: "테스터" } });
    fireEvent.change(screen.getByLabelText("생년월일"), { target: { value: "2000-01-01" } });
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: " tester " } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.change(screen.getByLabelText("비밀번호 확인"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "중복확인" }));
    await screen.findByText("사용 가능한 아이디입니다.");
    fireEvent.click(screen.getByRole("button", { name: "회원가입", exact: true }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/setup/preferences"));
    expect(localStorage.getItem("accessToken")).toBe("signup-token");
    expect(fetch.mock.calls[0][0]).toBe("/api/auth/check-login-id?loginId=tester");
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
        name: "테스터", birthDate: "2000-01-01", loginId: "tester", password: "password123",
    });
});

test("signup requires matching passwords and rechecking a changed login ID", async () => {
    mount("/signup");
    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: "테스터" } });
    fireEvent.change(screen.getByLabelText("생년월일"), { target: { value: "2000-01-01" } });
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "tester@example.com" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.change(screen.getByLabelText("비밀번호 확인"), { target: { value: "otherpassword" } });
    fireEvent.click(screen.getByRole("button", { name: "중복확인" }));
    await screen.findByText("사용 가능한 아이디입니다.");
    const submit = screen.getByRole("button", { name: "회원가입", exact: true });
    expect(submit.disabled).toBe(true);
    expect(screen.getByText("비밀번호가 일치하지 않습니다.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("비밀번호 확인"), { target: { value: "password123" } });
    expect(submit.disabled).toBe(false);
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "different@example.com" } });
    expect(submit.disabled).toBe(true);
    expect(screen.queryByText("사용 가능한 아이디입니다.")).toBe(null);
    expect(fetch).toHaveBeenCalledTimes(1);
});

test("an already taken login ID keeps signup disabled", async () => {
    fetch.mockResolvedValueOnce(new Response(JSON.stringify({ available: false })));
    mount("/signup");
    fireEvent.change(await screen.findByLabelText("아이디"), { target: { value: "tester@example.com" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.change(screen.getByLabelText("비밀번호 확인"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "중복확인" }));
    await screen.findByText("이미 사용 중인 아이디입니다.");
    expect(screen.getByRole("button", { name: "회원가입", exact: true }).disabled).toBe(true);
    expect(fetch).toHaveBeenCalledTimes(1);
});

test("a late availability response cannot approve an edited login ID", async () => {
    let resolveCheck;
    fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveCheck = resolve; }));
    mount("/signup");
    fireEvent.change(await screen.findByLabelText("이름"), { target: { value: "테스터" } });
    fireEvent.change(screen.getByLabelText("생년월일"), { target: { value: "2000-01-01" } });
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "tester@example.com" } });
    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "password123" } });
    fireEvent.change(screen.getByLabelText("비밀번호 확인"), { target: { value: "password123" } });
    fireEvent.click(screen.getByRole("button", { name: "중복확인" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("아이디"), { target: { value: "changed@example.com" } });
    await act(async () => { resolveCheck(new Response(JSON.stringify({ available: true }))); });
    expect(screen.queryByText("사용 가능한 아이디입니다.")).toBe(null);
    expect(screen.getByRole("button", { name: "회원가입", exact: true }).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "중복확인" }));
    await screen.findByText("사용 가능한 아이디입니다.");
    expect(fetch.mock.calls[1][0]).toBe("/api/auth/check-login-id?loginId=changed%40example.com");
    expect(screen.getByRole("button", { name: "회원가입", exact: true }).disabled).toBe(false);
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

test("OAuth removes URL credentials and goes directly to preferences, then profile", async () => {
    const kakaoUser = { id: 2, nickname: "카카오", linkedProviders: ["KAKAO"], email: null };
    fetch.mockResolvedValue(new Response(JSON.stringify(kakaoUser)));
    mount("/oauth2/callback#token=oauth-token");
    await screen.findByRole("heading", { name: "선호 정보 설정" });
    await at("/setup/preferences");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe("Bearer oauth-token");
    fireEvent.click(screen.getByRole("button", { name: "선호정보 저장 테스트" }));
    await screen.findByRole("heading", { name: "회원정보", exact: true });
    await at("/profile");
    expect(fetch).toHaveBeenCalledTimes(1);
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
