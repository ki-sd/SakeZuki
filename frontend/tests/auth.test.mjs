import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import ts from "typescript";
import axios from "axios";

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes,no) => { resolve=yes; reject=no; });
    return { promise, resolve, reject };
}

function setup(pathname = "/sake") {
    const calls = [];
    const wire = [];
    const members = new Map();
    let cookie = 1;
    let nextToken = 0;
    let failRefresh = false;
    let handle = async config => ({ config, status: 200, data: "public-result", headers: {}, statusText: "OK" });
    const api = axios.create({ adapter(config) { wire.push(config); return handle(config); } });
    const hooks = { post: async () => {}, me: async () => {} };
    const transport = {
        async get(url, config) {
            calls.push(["get", url]);
            if (url === "/csrf") return { data: { token: "csrf", headerName: "X-CSRF-TOKEN" } };
            const token = config.headers.Authorization.slice(7);
            const memberNo = members.get(token);
            await hooks.me(memberNo);
            return { data: { memberNo, email: memberNo===1 ? "user@example.com" : "second@example.com", roles: ["ROLE_USER"] } };
        },
        async post(url, data) {
            calls.push(["post", url]);
            await hooks.post(url);
            if (url === "/refresh" && failRefresh) throw new axios.AxiosError("expired","ERR_BAD_REQUEST",undefined,undefined,{ status: 401 });
            if (url === "/login") cookie = data.email === "second@example.com" ? 2 : 1;
            if (url === "/logout") cookie = 0;
            const accessToken = "candidate-" + ++nextToken;
            members.set(accessToken,cookie);
            return { data: { accessToken, tokenType: "Bearer", expiresIn: 900 } };
        },
    };
    const bindings = { create: () => transport, isAxiosError: axios.isAxiosError, isCancel: axios.isCancel };
    const exports = {};
    const source = fs.readFileSync(new URL("../app/api/auth.ts", import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
    vm.runInNewContext(compiled, {
        exports, URL, AbortController, AbortSignal, navigator: {}, window: { location: { pathname } },
        sessionStorage: { getItem: () => null, removeItem() {} },
        require(name) { if (name === "axios") return bindings; if (name === "./axios") return api; throw new Error(name); },
    });
    return { auth: exports, api, calls, wire, hooks, setAdapter(fn) { handle=fn; }, failRefresh() { failRefresh=true; },
        sharedCookie(member) { cookie=member; } };
}

function unauthorized(config) {
    return new axios.AxiosError("401","ERR_BAD_REQUEST",config,undefined,{ status: 401, config });
}
const first = "user@example.com";
const second = "second@example.com";

test("concurrent refreshes share a request and restore the current member", async () => {
    const { auth, calls } = setup();
    await Promise.all([auth.refresh(), auth.refresh(), auth.refresh()]);
    assert.equal(calls.filter(([method, url]) => method === "post" && url === "/refresh").length, 1);
    assert.equal(auth.authStore.snapshot().status, "authenticated");
    assert.equal(auth.authStore.snapshot().member.email, first);
});

test("OAuth callback exchange runs only once across repeated mounts", async () => {
    const { auth, calls } = setup("/oauth/callback");
    await auth.bootstrap();
    await Promise.all([auth.completeOAuth(), auth.completeOAuth()]);
    assert.equal(calls.filter(([method,url]) => method==="post" && url==="/social/token").length,1);
    assert.equal(calls.filter(([method,url]) => method==="post" && url==="/refresh").length,0);
});

test("social signup bootstrap preserves the pending server session", async () => {
    const { auth, calls } = setup("/signup/social");
    await auth.bootstrap();
    assert.equal(calls.length,0);
    await auth.socialSignup({ address1: "fixture" });
    assert.equal(auth.authStore.snapshot().member.memberNo,1);
    assert.equal(calls.filter(([method,url]) => method==="post" && url==="/social/signup").length,1);
});

test("logout revokes local authentication before the server revokes the cookie", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    const release = deferred();
    h.hooks.post = async url => { if (url === "/logout") await release.promise; };
    const logout = h.auth.logout();
    assert.equal(h.auth.authStore.snapshot().status,"anonymous");
    assert.equal(h.auth.authStore.snapshot().member,null);
    release.resolve();
    await logout;
});

test("401 retries a protected request once with the original generation", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    const before = h.auth.captureSession();
    h.setAdapter(async config => { throw unauthorized(config); });
    await assert.rejects(h.api.get("/user/me"));
    assert.equal(h.wire.length,2);
    assert.equal(h.wire[0]._authSession.generation,before.generation);
    assert.equal(h.wire[1]._authSession.generation,before.generation);
    assert.equal(h.auth.captureSession().generation,before.generation);
    assert.notEqual(h.wire[0].headers.get("Authorization"),h.wire[1].headers.get("Authorization"));
});

test("expired refresh logs out but public sake requests can still succeed", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    h.failRefresh();
    h.setAdapter(async config => {
        if (config.headers.has("Authorization")) throw unauthorized(config);
        return { data: "anonymous", config };
    });
    assert.equal((await h.api.get("/sake/list")).data,"anonymous");
    assert.equal(h.auth.authStore.snapshot().status,"anonymous");
    assert.equal(h.wire[1].headers.has("Authorization"),false);
});

test("return destinations reject external URLs and authentication loops", () => {
    const { auth } = setup();
    for (const value of ["https://evil.example","//evil.example","/\\evil.example","/login","/a/../login","/login/","/%5c%5cevil.example","/%2f%2fevil.example",null])
        assert.equal(auth.safeReturnTo(value),"/sake");
    assert.equal(auth.safeReturnTo("/account?tab=profile"),"/account?tab=profile");
});

test("expired login can read fixed public examples but never anonymously retry paid APIs or writes", async () => {
    for (const [method,path,fallsBack] of [["get","/examples/recommendations",true],["post","/recommend/sake",false],["post","/sake/list",false]]) {
        const h = setup();
        await h.auth.login(first,"password");
        h.failRefresh();
        h.setAdapter(async config => {
            if (config.headers.has("Authorization")) throw unauthorized(config);
            return { data: "anonymous", config };
        });
        const request = h.api[method](path);
        if (fallsBack) assert.equal((await request).data,"anonymous");
        else await assert.rejects(request);
        assert.equal(h.wire.length,fallsBack?2:1);
    }
});

for (const email of [first,second]) {
    test(`stale responses for protected writes are cancelled before another session as ${email}`, async () => {
        const h = setup();
        await h.auth.login(first,"password");
        const gate = deferred();
        h.setAdapter(config => gate.promise.then(() => {
            if (config.url.endsWith("/cancel")) return { config,data: "old success" };
            throw unauthorized(config);
        }));
        const paths = ["/user/sake-submissions","/user/sake-submissions/uploads","/user/sake-submissions/fixture/revisions",
            "/user/sake-submissions/fixture/cancel","/recommend/sake"];
        const pending = Promise.allSettled(paths.map(path => h.api.post(path,{})));
        assert.equal(h.wire.length,paths.length);
        await h.auth.logout();
        await h.auth.login(email,"password");
        assert.ok(h.wire.every(config => config.signal.aborted));
        gate.resolve();
        assert.ok((await pending).every(result => result.status==="rejected"));
        assert.equal(h.wire.length,paths.length);
    });
}

for (const fail of [false,true]) {
    test(`in-flight refresh ${fail?"failure":"success"} cannot replace a newer authentication operation`, async () => {
        const h = setup();
        await h.auth.login(first,"password");
        const entered = deferred(), release = deferred();
        h.hooks.post = async url => {
            if (url==="/refresh") {
                entered.resolve();
                await release.promise;
                if (fail) throw new Error("fixture offline");
            }
        };
        const old = assert.rejects(h.auth.refresh());
        await entered.promise;
        const logout = h.auth.logout();
        const login = h.auth.login(second,"password");
        release.resolve();
        await Promise.all([old,logout,login]);
        h.hooks.post = async () => {};
        assert.equal(h.auth.authStore.snapshot().member.memberNo,2);
        await h.auth.refresh();
        assert.equal(h.auth.authStore.snapshot().member.memberNo,2);
    });
}

test("a different member in a shared refresh cookie invalidates the old session without replay", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    h.sharedCookie(2);
    h.setAdapter(async config => { throw unauthorized(config); });
    await assert.rejects(h.api.post("/user/sake-submissions",{}));
    assert.equal(h.wire.length,1);
    assert.equal(h.auth.authStore.snapshot().status,"anonymous");
});

test("caller cancellation does not trigger refresh or disturb the current session", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    const caller = new AbortController(), response = deferred();
    h.setAdapter(config => response.promise.then(() => { throw unauthorized(config); }));
    const pending = assert.rejects(h.api.get("/user/me",{ signal: caller.signal }));
    caller.abort();
    assert.equal(h.wire[0].signal.aborted,true);
    response.resolve();
    await pending;
    assert.equal(h.calls.filter(([method,url]) => method==="post" && url==="/refresh").length,0);
    assert.equal(h.auth.authStore.snapshot().member.memberNo,1);
});

test("logout during the candidate /me lookup prevents a late login from publishing", async () => {
    const h = setup();
    const entered = deferred(), release = deferred();
    h.hooks.me = async () => { entered.resolve(); await release.promise; };
    const login = assert.rejects(h.auth.login(first,"password"));
    await entered.promise;
    const logout = h.auth.logout();
    release.resolve();
    await Promise.all([login,logout]);
    assert.equal(h.auth.authStore.snapshot().status,"anonymous");
});

test("the request interceptor refuses a retry whose session changes just before dispatch", async () => {
    const h = setup();
    await h.auth.login(first,"password");
    let transition;
    h.api.interceptors.request.use(config => {
        if (config._authRetried) transition = h.auth.login(second,"password");
        return config;
    },undefined,{ synchronous: true });
    h.setAdapter(async config => { throw unauthorized(config); });
    await assert.rejects(h.api.post("/recommend/sake",{}));
    await transition;
    assert.equal(h.wire.length,1);
    assert.equal(h.auth.authStore.snapshot().member.memberNo,2);
});

test("submission UTC dates preserve offsets and reject invalid expiration values", () => {
    const exports = {};
    const source = fs.readFileSync(new URL("../commons/submission-date.ts",import.meta.url),"utf8");
    vm.runInNewContext(ts.transpileModule(source,{ compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText,{ exports });
    assert.equal(exports.parseSubmissionDate("2026-01-01T00:00:00.123456"),Date.parse("2026-01-01T00:00:00.123Z"));
    assert.equal(exports.parseSubmissionDate("2026-01-01T09:00:00.123+09:00"),Date.parse("2026-01-01T00:00:00.123Z"));
    assert.equal(exports.parseSubmissionDate("2026-01-01T00:00:00Z"),Date.parse("2026-01-01T00:00:00Z"));
    assert.equal(exports.parseSubmissionDate("2026-02-30T09:00:00"),null);
    assert.equal(exports.parseSubmissionDate("invalid"),null);
    assert.equal(exports.submissionDate("invalid"),"시각 확인 불가");
});
