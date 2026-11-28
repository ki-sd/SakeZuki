import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";
import ts from "typescript";

function setup() {
    const storage = new Map(); const calls = []; let failure;
    const api = { async post(url, input, config) {
        calls.push({ url, input, key: config.headers["Idempotency-Key"] });
        if (failure) { const error = failure; failure = undefined; throw error; }
        return { data: { food: input.food, recommendations: [] } };
    } };
    const exports = {};
    const source = fs.readFileSync(new URL("../app/api/recommend.ts", import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
    vm.runInNewContext(compiled, { exports, crypto: webcrypto, TextEncoder,
        sessionStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
        require(name) {
            if (name === "axios") return { isAxiosError: error => error?.isAxiosError };
            if (name === "./axios") return api;
            if (name === "./auth") return { captureSession: () => ({memberNo: 1, generation: 1}), requireSession() {} };
            throw new Error(name);
        },
    });
    return { recommend: exports.recommendSake, storage, calls, fail(error) { failure = error; } };
}
test("concurrent clicks share one credit request; a new completed request uses a new key", async () => {
    const h = setup();
    await Promise.all([h.recommend({ food: "test-food" }), h.recommend({ food: "test-food" })]);
    assert.equal(h.calls.length, 1);
    const original = h.calls[0].key;
    await h.recommend({ food: "test-food" });
    assert.notEqual(h.calls[1].key, original);
});
test("lost response retains the key and stores no raw input", async () => {
    const h = setup(); h.fail({ isAxiosError: true });
    await assert.rejects(h.recommend({ food: "private-input" }));
    const original = h.calls[0].key;
    assert.equal(h.storage.size, 1);
    assert.equal([...h.storage.keys()].some(key => key.includes("private-input")), false);
    await h.recommend({ food: "private-input" });
    assert.equal(h.calls[1].key, original);
    assert.equal(h.storage.size, 0);
});
test("pending server request keeps its key; definitively refunded request permits a new attempt", async () => {
    const h = setup();
    h.fail({ isAxiosError: true, response: { status: 409, data: "추천을 처리 중입니다." } });
    await assert.rejects(h.recommend({ food: "test" })); assert.equal(h.storage.size, 1);
    h.fail({ isAxiosError: true, response: { status: 409, data: "실패한 요청은 복구되었습니다." } });
    await assert.rejects(h.recommend({ food: "test" })); assert.equal(h.storage.size, 0);
    assert.equal(h.calls[0].key, h.calls[1].key);
    await h.recommend({ food: "test" }); assert.notEqual(h.calls[1].key, h.calls[2].key);
});
