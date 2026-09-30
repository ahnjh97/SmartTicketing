import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { ApiError, apiUrl, request } from "../src/api/client.js";
import { authApi, userApi, theaterApi, ticketApi, notificationApi } from "../src/api/index.js";

const originalFetch = globalThis.fetch;
const originalStorage = globalThis.localStorage;
let calls;

beforeEach(() => {
    calls = [];
    const storage = new Map([["accessToken", "test-token"]]);
    globalThis.localStorage = {
        getItem: (key) => storage.get(key) ?? null,
        setItem: (key, value) => storage.set(key, String(value)),
        removeItem: (key) => storage.delete(key),
    };
    globalThis.fetch = async (url, options) => {
        calls.push({ url, ...options });
        return new Response(JSON.stringify({ id: 1 }));
    };
});

afterEach(() => {
    globalThis.fetch = originalFetch;
    if (originalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = originalStorage;
});

test("public login and signup omit any previous user's bearer token", async () => {
    const login = { loginId: "tester", password: "password123" };
    await authApi.login(login);
    await authApi.signup({ ...login, name: "테스터", birthDate: "2000-01-01" });
    assert.deepEqual(calls.map(({ url }) => url), ["/api/auth/login", "/api/auth/signup"]);
    for (const call of calls) {
        assert.equal(call.method, "POST");
        assert.equal(call.headers.Authorization, undefined);
        assert.equal(call.headers["Content-Type"], "application/json");
        assert.equal(call.credentials, "include");
    }
    assert.deepEqual(JSON.parse(calls[0].body), login);
});

test("preference update preserves theater order and repeated seat positions", async () => {
    const preferences = {
        preferredTheaterIds: [3, 1, 2],
        preferredSeatPositions: ["MIDDLE_MIDDLE", "MIDDLE_MIDDLE", "SIDE_FRONT"],
    };
    await userApi.update(preferences);
    assert.equal(calls[0].url, "/api/users/me");
    assert.equal(calls[0].method, "PATCH");
    assert.equal(calls[0].headers.Authorization, "Bearer test-token");
    assert.deepEqual(JSON.parse(calls[0].body), preferences);
    await userApi.me("callback-token");
    assert.equal(calls[1].headers.Authorization, "Bearer callback-token");
});

test("query values are encoded and nearby filters reach the existing endpoint", async () => {
    await authApi.checkLoginId("a&b + 한글");
    assert.equal(new URL(calls[0].url, "http://localhost").searchParams.get("loginId"), "a&b + 한글");
    assert.equal(calls[0].headers.Authorization, undefined);
    await theaterApi.nearby({ latitude: 37.5, longitude: 127, radius: 3000 });
    assert.equal(calls[1].url, "/api/theaters/nearby?latitude=37.5&longitude=127&radius=3000");
    assert.equal(apiUrl("/api/notifications", { unreadOnly: false, omitted: undefined }),
        "/api/notifications?unreadOnly=false");
});

test("ticket and notification clients use only the existing server routes", async () => {
    await ticketApi.mine();
    await ticketApi.one(8);
    await ticketApi.issue(10);
    await notificationApi.list(true);
    await notificationApi.read(7);
    await notificationApi.readAll();
    await notificationApi.delete(7);
    await userApi.preferenceOptions();
    await authApi.link("google");
    assert.deepEqual(calls.map(({ method, url }) => [method, url]), [
        ["GET", "/api/tickets"], ["GET", "/api/tickets/8"],
        ["POST", "/api/tickets/reservations/10"],
        ["GET", "/api/notifications?unreadOnly=true"],
        ["PATCH", "/api/notifications/7/read"], ["PATCH", "/api/notifications/read-all"],
        ["DELETE", "/api/notifications/7"], ["GET", "/api/users/preference-options"],
        ["POST", "/api/auth/link/GOOGLE"],
    ]);
    assert.equal(authApi.socialLoginUrl("KAKAO"), "/oauth2/authorization/kakao");
});

test("logout, withdrawal, and notification changes accept empty 204 responses", async () => {
    globalThis.fetch = async () => new Response(null, { status: 204 });
    assert.equal(await authApi.logout(), null);
    assert.equal(await userApi.withdraw(), null);
    assert.equal(await notificationApi.readAll(), null);
});

test("server error messages and HTTP status remain available to the screen", async () => {
    globalThis.fetch = async () => new Response(JSON.stringify({ message: "이미 사용 중인 아이디입니다." }), {
        status: 409,
    });
    await assert.rejects(authApi.signup({}), (error) => {
        assert.ok(error instanceof ApiError);
        assert.equal(error.status, 409);
        assert.equal(error.message, "이미 사용 중인 아이디입니다.");
        return true;
    });
    globalThis.fetch = async () => new Response("<html>unavailable</html>", { status: 503 });
    await assert.rejects(userApi.me(), { status: 503, message: "요청에 실패했습니다. (503)" });
    globalThis.fetch = async () => new Response(null, { status: 401 });
    await assert.rejects(userApi.me(), { status: 401 });
});

test("protected calls require a token and malformed success responses fail clearly", async () => {
    globalThis.localStorage = { getItem: () => null };
    await assert.rejects(request("/api/users/me"), { status: 401 });
    assert.equal(calls.length, 0);
    globalThis.fetch = async () => new Response("<html>not JSON</html>");
    await assert.rejects(authApi.login({}), { message: "서버 응답을 처리할 수 없습니다." });
});
