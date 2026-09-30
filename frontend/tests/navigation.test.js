import assert from "node:assert/strict";
import { test } from "node:test";
import { PAGE_PATHS, pageForPath, redirectPage } from "../src/navigation.js";

test("each existing screen has a distinct address that survives direct loading", () => {
    assert.equal(new Set(Object.values(PAGE_PATHS)).size, Object.keys(PAGE_PATHS).length);
    for (const [page, path] of Object.entries(PAGE_PATHS)) {
        assert.equal(pageForPath(path), page);
        assert.equal(pageForPath(`${path}/`), page);
    }
});

test("guests can browse public screens and are redirected from member screens", () => {
    for (const page of ["home", "movies", "theaters", "login", "signup"]) {
        assert.equal(redirectPage({ page, authenticated: false }), null);
    }
    for (const page of ["profile", "preferences", "nicknameSetup", "preferenceSetup", "callback"]) {
        assert.equal(redirectPage({ page, authenticated: false }), "login");
    }
});

test("nickname setup takes priority over preferences without redirecting itself", () => {
    const state = { authenticated: true, nicknameSetupRequired: true, preferenceSetupRequired: true };
    for (const page of ["home", "profile", "preferences", "callback", "preferenceSetup"]) {
        assert.equal(redirectPage({ ...state, page }), "nicknameSetup");
    }
    assert.equal(redirectPage({ ...state, page: "nicknameSetup" }), null);
});

test("nickname completion advances to the existing initial preference screen", () => {
    const state = { authenticated: true, nicknameSetupRequired: false, preferenceSetupRequired: true };
    assert.equal(redirectPage({ ...state, page: "nicknameSetup" }), "preferenceSetup");
    assert.equal(redirectPage({ ...state, page: "preferences" }), "preferenceSetup");
    assert.equal(redirectPage({ ...state, page: "preferenceSetup" }), null);
});

test("completed users keep deep links and leave completed setup and auth pages", () => {
    const state = { authenticated: true, nicknameSetupRequired: false, preferenceSetupRequired: false };
    for (const page of ["home", "movies", "theaters", "profile", "preferences"]) {
        assert.equal(redirectPage({ ...state, page }), null);
    }
    for (const page of ["nicknameSetup", "preferenceSetup", "login", "signup", "callback"]) {
        assert.equal(redirectPage({ ...state, page }), "profile");
    }
});
