import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { restoreSession } from "../src/auth/bootstrap.js";
import {
    getAccessToken, setAccessToken, needsNicknameSetup, needsPreferenceSetup,
    nicknameSetupKey, subscribeToSessionExpiration,
} from "../src/auth/session.js";
import { userApi, authApi } from "../src/api/index.js";

const originalFetch = globalThis.fetch;
const originalStorage = globalThis.localStorage;
let unsubscribe;

beforeEach(() => {
    const storage = new Map();
    globalThis.localStorage = {
        getItem: (key) => storage.get(key) ?? null,
        setItem: (key, value) => storage.set(key, String(value)),
        removeItem: (key) => storage.delete(key),
    };
    unsubscribe = () => {};
});

afterEach(() => {
    unsubscribe();
    globalThis.fetch = originalFetch;
    if (originalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = originalStorage;
});

test("OAuth callback uses the issued token for member lookup", async () => {
    let authorization;
    globalThis.fetch = async (_, options) => {
        authorization = options.headers.Authorization;
        return new Response(JSON.stringify({ id: 42 }));
    };
    const restored = await restoreSession("http://localhost/oauth2/callback#token=callback-token");
    assert.equal(authorization, "Bearer callback-token");
    assert.equal(getAccessToken(), "callback-token");
    assert.deepEqual(restored, { user: { id: 42 }, token: "callback-token" });
});

test("OAuth error is decoded once and does not make an authenticated request", async () => {
    globalThis.fetch = () => assert.fail("An OAuth error must not fetch a member");
    await assert.rejects(restoreSession("http://localhost/oauth2/callback?error="
        + encodeURIComponent("로그인 실패: 100%")), { message: "로그인 실패: 100%" });
    assert.equal(getAccessToken(), null);
    assert.deepEqual(await restoreSession("http://localhost/login"), { user: null, token: null });
});

test("authenticated 401 expires the session while public errors and 403 preserve it", async () => {
    setAccessToken("current-token");
    let expirations = 0;
    unsubscribe = subscribeToSessionExpiration(() => { expirations++; });
    globalThis.fetch = async () => new Response(null, { status: 401 });
    await assert.rejects(authApi.login({}), { status: 401 });
    assert.equal(getAccessToken(), "current-token");
    globalThis.fetch = async () => new Response(null, { status: 403 });
    await assert.rejects(userApi.me(), { status: 403 });
    assert.equal(getAccessToken(), "current-token");
    globalThis.fetch = async () => new Response(null, { status: 401 });
    await assert.rejects(userApi.me(), { status: 401 });
    assert.equal(getAccessToken(), null);
    assert.equal(expirations, 1);
});

test("a late 401 from an old login cannot expire the current login", async () => {
    setAccessToken("new-token");
    unsubscribe = subscribeToSessionExpiration(() => assert.fail("New session must stay active"));
    globalThis.fetch = async () => new Response(null, { status: 401 });
    await assert.rejects(userApi.me("old-token"), { status: 401 });
    assert.equal(getAccessToken(), "new-token");
});

test("existing Kakao and preference completion rules remain distinct", () => {
    const user = { id: 1, linkedProviders: ["KAKAO"], email: null };
    assert.equal(needsNicknameSetup(user), true);
    localStorage.setItem(nicknameSetupKey(user.id), "true");
    assert.equal(needsNicknameSetup(user), false);
    assert.equal(needsPreferenceSetup(user), true);
    assert.equal(needsPreferenceSetup({ ...user, birthDate: "2000-01-01", address: "서울",
        preferredTheaters: [{}, {}, {}], preferredSeats: ["MIDDLE_MIDDLE"] }), false);
    assert.equal(needsNicknameSetup({ ...user, id: 2, email: "test@example.com" }), false);
    assert.equal(needsNicknameSetup({ ...user, id: 2, linkedProviders: ["LOCAL"] }), false);
});
