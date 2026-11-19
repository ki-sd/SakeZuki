import { test, expect } from "@playwright/test";

test("lost recommendation response replays without charging twice; history reopening is free", async ({ page, context }) => {
    test.setTimeout(60000);
    test.skip(!process.env.AUTH_TEST_FOOD || !process.env.AUTH_TEST_LOGIN_EMAIL, "Requires the isolated synthetic credit fixtures");
    await page.goto("/login");
    await page.getByRole("textbox", { name: "이메일", exact: true }).fill(process.env.AUTH_TEST_LOGIN_EMAIL!);
    await page.getByLabel("비밀번호", { exact: true }).fill(process.env.AUTH_TEST_LOGIN_PASSWORD!);
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await expect(page.getByRole("button", { name: "로그아웃", exact: true })).toBeVisible();
    await page.goto("/recommend");
    await expect(page.getByText(/잔여 크레딧 \d+개/)).toBeVisible();
    const label = page.getByText(/잔여 크레딧 \d+개/);
    const before = Number((await label.textContent())!.match(/\d+/)![0]);
    const input=page.getByRole("textbox", { name: "음식", exact: true });
    await input.fill(process.env.AUTH_TEST_FOOD!);
    const keys: string[]=[];
    await page.route("**/api/recommend/sake", async route => {
        keys.push(route.request().headers()["idempotency-key"]);
        const response=await route.fetch();expect(response.status()).toBe(200);
        // 서버의 확정 이후 브라우저 연결만 끊어 실제 응답 유실을 재현한다.
        await route.abort();
    });
    await page.getByRole("button", { name: "추천받기", exact: true }).click();
    await expect(label).toContainText(`잔여 크레딧 ${before-1}개`);
    await page.unroute("**/api/recommend/sake");
    page.on("request", request => { if(new URL(request.url()).pathname==="/api/recommend/sake") keys.push(request.headers()["idempotency-key"]); });
    await page.getByRole("button", { name: "추천받기", exact: true }).click();
    await expect(page.getByText("E2E 당시 추천 이유").first()).toBeVisible();
    expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);
    await expect(label).toContainText(`잔여 크레딧 ${before-1}개`);
    await page.goto("/account");
    await expect(page.getByText(`잔여 크레딧 ${before-1}개`,{exact:true})).toBeVisible();
    await page.getByRole("button",{name:"결과 다시 보기",exact:true}).first().click();
    await expect(page.getByRole("heading",{name:"당시 제공된 추천 결과"})).toBeVisible();
    await expect(page.getByText(`잔여 크레딧 ${before-1}개`,{exact:true})).toBeVisible();
    expect(keys).toHaveLength(2);
    expect((await context.cookies()).some(cookie=>cookie.name==="sakezuki_refresh"&&cookie.httpOnly)).toBe(true);
});
