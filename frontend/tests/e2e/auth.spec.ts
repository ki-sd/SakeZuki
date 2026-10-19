import { test, expect, type APIRequestContext } from "@playwright/test";

async function csrfPost(request: APIRequestContext, path: string, data: unknown = {}) {
    const csrfResponse = await request.get("/api/auth/csrf");
    expect(csrfResponse.status()).toBe(200);
    const csrf = await csrfResponse.json();
    return request.post(path, { data, headers: { [csrf.headerName]: csrf.token } });
}

test("anonymous requests: CSRF, 401 and public sake API through the real proxy", async ({ request }) => {
    expect((await request.get("/api/user/me")).status()).toBe(401);
    expect((await request.post("/api/auth/login", { data: {} })).status()).toBe(403);
    expect((await csrfPost(request, "/api/auth/refresh")).status()).toBe(401);
    const list = await request.get("/api/sake/list"); expect(list.status()).toBe(200);
    const data = await list.json(); expect(data.list.length).toBeGreaterThan(0);
    expect((await request.get(`/api/sake/${data.list[0].no}`)).status()).toBe(200);
    expect((await request.post("/api/recommend/sake", { data: { food: " " } })).status()).toBe(400);
});

test("Google start and failed callback preserve routing through Next.js", async ({ request }) => {
    const start = await request.get("/oauth2/authorization/google", { maxRedirects: 0 });
    expect(start.status()).toBe(302);
    const destination = new URL(start.headers().location);
    expect(destination.origin).toBe("https://accounts.google.com");
    expect(destination.searchParams.get("redirect_uri")).toBe(`${process.env.AUTH_TEST_BASE_URL ?? "http://localhost:3000"}/login/oauth2/code/google`);
    expect(!!destination.searchParams.get("state")).toBe(true);
    expect(start.headers()["set-cookie"].includes("HttpOnly")).toBe(true);
    const failure = await request.get("/login/oauth2/code/google?error=access_denied&state=invalid", { maxRedirects: 0 });
    expect(failure.status()).toBe(302);
    expect(new URL(failure.headers().location).pathname).toBe("/login");
});

test("missing social session and callback cancellation show recovery links", async ({ page }) => {
    await page.goto("/signup/social");
    await expect(page.getByRole("link", { name: "Google 로그인 다시 시작", exact: true })).toBeVisible();
    await page.goto("/login?error=oauth");
    await expect(page.getByRole("alert")).toContainText("취소되었거나 실패");
    await page.goto("/account");
    await expect(page).toHaveURL(/\/login\?returnTo=/);
});

test("phone input accepts only digits and inserts 3-4-4 hyphens", async ({ page }) => {
    // Only the one-time signup-info response is stubbed for this input-only test.
    await page.route("**/api/auth/social/signup-info", route => route.fulfill({ json: { provider: "GOOGLE", email: "form@example.invalid", name: "입력 테스트" } }));
    await page.goto("/signup/social");
    const phone = page.getByRole("textbox", { name: "전화번호", exact: true });
    await phone.fill("010abc"); await expect(phone).toHaveValue("010");
    await phone.fill("01012345678"); await expect(phone).toHaveValue("010-1234-5678");
    await phone.fill("010-1234-5678"); await expect(phone).toHaveValue("010-1234-5678");
    await phone.fill("letters"); await expect(phone).toHaveValue("");
    await expect(phone).toHaveAttribute("inputmode", "numeric");
});

test("email login, reload restoration, rotation, 403 and logout in Chromium", async ({ page, context }) => {
    test.setTimeout(110000);
    test.skip(!process.env.AUTH_TEST_LOGIN_EMAIL || !process.env.AUTH_TEST_LOGIN_PASSWORD, "Requires the isolated E2E server fixtures");
    await page.goto("/account"); await expect(page).toHaveURL(/\/login\?returnTo=/);
    await page.getByRole("textbox", { name: "이메일", exact: true }).fill(process.env.AUTH_TEST_LOGIN_EMAIL!);
    await page.getByLabel("비밀번호", { exact: true }).fill("wrong-password");
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await expect(page.getByRole("alert")).toBeVisible();
    await page.getByLabel("비밀번호", { exact: true }).fill(process.env.AUTH_TEST_LOGIN_PASSWORD!);
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
    const old = (await context.cookies()).find(c => c.name === "sakezuki_refresh")!;
    expect(old.httpOnly).toBe(true); expect(old.path).toBe("/api/auth"); expect(old.sameSite).toBe("Lax");
    await page.reload();
    await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
    const rotated = (await context.cookies()).find(c => c.name === "sakezuki_refresh")!;
    expect(old.value !== rotated.value).toBe(true);
    const tokens = await csrfPost(context.request, "/api/auth/refresh"); expect(tokens.status()).toBe(200);
    const token = (await tokens.json()).accessToken;
    expect((await context.request.get("/api/admin/e2e-probe", { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(403);
    // The E2E server issues 2-second tokens. Wait for real expiration plus the default 60-second clock tolerance.
    await expect.poll(async () => (await context.request.get("/api/user/me", {
        headers: { Authorization: `Bearer ${token}` },
    })).status(), { timeout: 75000, intervals: [1000, 2000] }).toBe(401);
    let refreshes = 0;
    page.on("request", request => { if (new URL(request.url()).pathname === "/api/auth/refresh" && request.method() === "POST") refreshes++; });
    await page.getByRole("link", { name: "사케 목록", exact: true }).click();
    await expect(page.getByRole("heading", { name: "사케 목록", exact: true })).toBeVisible();
    await expect.poll(() => refreshes).toBe(1);
    await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
    await page.getByRole("link", { name: "내 계정", exact: true }).click();
    await page.getByRole("button", { name: "로그아웃", exact: true }).click();
    await expect(page).toHaveURL(/\/login\?returnTo=/);
    expect((await csrfPost(context.request, "/api/auth/refresh")).status()).toBe(401);
});
