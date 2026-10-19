import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import ts from "typescript";

function setup(pathname = "/sake") {
    const calls = [];
    let requestInterceptor, responseInterceptor;
    let failRefresh = false;
    const api = async config => { calls.push(["retry", config]); return { data: "public-result" }; };
    api.interceptors = {
        request: { use(fn) { requestInterceptor = fn; } },
        response: { use(ok, fail) { responseInterceptor = fail; } },
    };
    const transport = {
        async get(url) {
            calls.push(["get", url]);
            if (url === "/csrf") return { data: { token: "csrf", headerName: "X-CSRF-TOKEN" } };
            return { data: { memberNo: 1, email: "user@example.com", roles: ["ROLE_USER"] } };
        },
        async post(url) {
            calls.push(["post", url]);
            if (url === "/refresh" && failRefresh) throw { isAxiosError: true, response: { status: 401 } };
            return { data: { accessToken: "access", tokenType: "Bearer", expiresIn: 900 } };
        },
    };
    const axios = { create: () => transport, isAxiosError: error => error?.isAxiosError === true };
    const exports = {};
    const source = fs.readFileSync(new URL("../app/api/auth.ts", import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
    vm.runInNewContext(compiled, {
        exports, navigator: {}, window: { location: { pathname } }, sessionStorage: { getItem: () => null, removeItem() {} },
        require(name) { if (name === "axios") return axios; if (name === "./axios") return api; throw new Error(name); },
    });
    return { auth: exports, calls, request: config => requestInterceptor(config), response: error => responseInterceptor(error), failRefresh() { failRefresh = true; } };
}

test("concurrent refreshes share a request and restore the current member", async () => {
    const { auth, calls } = setup();
    await Promise.all([auth.refresh(), auth.refresh(), auth.refresh()]);
    assert.equal(calls.filter(([method, url]) => method === "post" && url === "/refresh").length, 1);
    assert.equal(auth.authStore.snapshot().status, "authenticated");
    assert.equal(auth.authStore.snapshot().member.email, "user@example.com");
});

test("OAuth callback exchange runs only once across repeated mounts", async () => {
    const { auth, calls } = setup("/oauth/callback");
    await auth.bootstrap();
    await Promise.all([auth.completeOAuth(), auth.completeOAuth()]);
    assert.equal(calls.filter(([method, url]) => method === "post" && url === "/social/token").length, 1);
    assert.equal(calls.filter(([method, url]) => method === "post" && url === "/refresh").length, 0);
});

test("social signup bootstrap preserves the pending server session", async () => {
    const { auth, calls } = setup("/signup/social");
    await auth.bootstrap();
    assert.equal(calls.length, 0);
});

test("logout clears in-memory authentication after the server revokes the cookie", async () => {
    const { auth } = setup();
    await auth.login("user@example.com", "password");
    assert.equal(auth.authStore.snapshot().status, "authenticated");
    await auth.logout();
    assert.equal(auth.authStore.snapshot().status, "anonymous");
    assert.equal(auth.authStore.snapshot().member, null);
});

test("401 retries a protected request once and prevents infinite refresh", async () => {
    const { auth, response, calls } = setup();
    await auth.login("user@example.com", "password");
    const config = { url: "/user/me", headers: { Authorization: "Bearer access" } };
    await response({ config, response: { status: 401 } });
    assert.equal(config._authRetried, true);
    const before = calls.length;
    await assert.rejects(response({ config, response: { status: 401 } }));
    assert.equal(calls.length, before);
});

test("expired refresh logs out but public sake requests can still succeed", async () => {
    const harness = setup();
    await harness.auth.login("user@example.com", "password");
    harness.failRefresh();
    const config = { url: "/sake/list", headers: { Authorization: "Bearer access" } };
    await harness.response({ config, response: { status: 401 } });
    assert.equal(harness.auth.authStore.snapshot().status, "anonymous");
    assert.equal(config.headers.Authorization, undefined);
});

test("return destinations reject external URLs and authentication loops", () => {
    const { auth } = setup();
    for (const value of ["https://evil.example", "//evil.example", "/\\evil.example", "/login", null])
        assert.equal(auth.safeReturnTo(value), "/sake");
    assert.equal(auth.safeReturnTo("/account?tab=profile"), "/account?tab=profile");
});
