import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

test("public landing and both editorial pairing directions work without paid requests", async ({ page }) => {
    const paid: string[] = [];
    page.on("request", request => { if (new URL(request.url()).pathname.startsWith("/api/recommend/")) paid.push(request.url()); });
    await page.goto("/");
    await expect(page).toHaveTitle(/SakeZuki/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("오늘의 음식에서");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "본문으로 건너뛰기" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator("main")).toHaveAttribute("id", "main-content");
    await expect(page.locator('a[href="/sake"]').first()).toBeVisible();
    await page.goto("/examples");
    await expect(page.getByText("직접 작성한 편집 예시", { exact: true })).toBeVisible();
    const examples = await (await page.request.get("/api/examples/recommendations")).json();
    expect(examples).toHaveLength(4);
    for (const example of examples) {
        expect(example.sourceType).toBe("EDITORIAL_SAMPLE");
        await page.getByRole("button", { name: example.title, exact: true }).click();
        await expect(page.getByRole("button", { name: example.title, exact: true })).toHaveAttribute("aria-pressed", "true");
        await expect(page.getByText(/신규 AI 호출이나 크레딧 사용은 발생하지 않습니다/)).toBeVisible();
    }
    expect(paid).toHaveLength(0);
    expect((await page.request.get("/api/user/recommendations/not-owned")).status()).toBe(401);
});

test("mobile landing remains usable when public examples are unavailable or empty", async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await page.route("**/api/examples/recommendations", route => route.fulfill({ status: 503, contentType: "application/json", body: "{}" }));
    await page.goto("/");
    await expect(page.getByText("지금은 예시를 불러올 수 없습니다. 사케 탐색은 계속 이용할 수 있습니다.")).toBeVisible();
    await expect(page.locator('a[href="/sake"]').first()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.unroute("**/api/examples/recommendations");
    await page.route("**/api/examples/recommendations", route => route.fulfill({ status: 200, contentType: "application/json", body: "[]" }));
    await page.goto("/examples");
    await expect(page.getByText("공개 예시를 준비하고 있습니다. 먼저 사케 목록을 둘러보세요.")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("duplicate warning offers free history before an explicitly paid new request", async ({ page }) => {
    test.setTimeout(60000);
    // 분리 DB의 합성 캐시만 사용한다. 실회원 또는 외부 Gemini를 이용하지 않는다.
    expect(process.env.AUTH_TEST_FOOD, "Run scripts/test_auth_integration.py --e2e").toBeTruthy();
    await page.goto("/login");
    await page.getByRole("textbox", { name: "이메일", exact: true }).fill(process.env.AUTH_TEST_LOGIN_EMAIL!);
    await page.getByLabel("비밀번호", { exact: true }).fill(process.env.AUTH_TEST_LOGIN_PASSWORD!);
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
    // 다른 테스트 실행 순서에 기대지 않고 이 사용자의 기존 기록을 독립적으로 만든다.
    const csrf = await (await page.request.get("/api/auth/csrf")).json();
    const tokens = await page.request.post("/api/auth/refresh", { headers: { [csrf.headerName]: csrf.token } });
    expect(tokens.status()).toBe(200);
    const token = (await tokens.json()).accessToken;
    const original = await page.request.post("/api/recommend/sake", { headers: { Authorization: `Bearer ${token}`, "Idempotency-Key": randomUUID() }, data: { food: process.env.AUTH_TEST_FOOD! } });
    expect(original.status()).toBe(200);
    const paid: string[] = [];
    page.on("request", request => { if (new URL(request.url()).pathname === "/api/recommend/sake") paid.push(request.headers()["idempotency-key"]); });
    await page.goto("/recommend");
    const balance = page.getByText(/잔여 크레딧 \d+개/);
    await expect(balance).toBeVisible();
    const before = Number((await balance.textContent())!.match(/\d+/)![0]);
    await page.getByRole("textbox", { name: "음식", exact: true }).fill(`  ${process.env.AUTH_TEST_FOOD!}  `);
    await page.getByRole("button", { name: "추천받기", exact: true }).click();
    const notice = page.getByRole("region", { name: "이전에 동일한 조건으로 추천받은 기록이 있습니다." });
    await expect(notice).toBeVisible();
    expect(paid).toHaveLength(0);
    await page.getByRole("link", { name: "이전 추천 결과 보기 - 무료", exact: true }).click();
    await expect(page.getByText("E2E 당시 추천 이유").first()).toBeVisible();
    await expect(page.getByText(`잔여 크레딧 ${before}개`, { exact: true })).toBeVisible();
    expect(paid).toHaveLength(0);
    await page.goto(`/recommend?food=${encodeURIComponent(process.env.AUTH_TEST_FOOD!)}`);
    await page.getByRole("button", { name: "추천받기", exact: true }).click();
    await expect(notice).toBeVisible();
    await page.getByRole("button", { name: "새롭게 추천받기 - 1크레딧", exact: true }).dblclick();
    await expect(page.getByText("E2E 당시 추천 이유").first()).toBeVisible();
    await expect(balance).toContainText(`잔여 크레딧 ${before - 1}개`);
    expect(paid).toHaveLength(1);
});
