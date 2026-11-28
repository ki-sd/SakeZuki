import { expect, test, type Page } from "@playwright/test";

async function loginFor(page: Page, path: string) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/login\?returnTo=/);
    await page.getByRole("textbox", { name: "이메일", exact: true }).fill(process.env.AUTH_TEST_LOGIN_EMAIL!);
    await page.getByLabel("비밀번호", { exact: true }).fill(process.env.AUTH_TEST_LOGIN_PASSWORD!);
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await expect(page).toHaveURL(new RegExp(path+"$"));
}

test("member submits a private image, retries a lost receipt and sees the saved item", async ({ page }) => {
    test.setTimeout(110000);
    test.skip(!process.env.AUTH_TEST_LOGIN_EMAIL || !process.env.AUTH_TEST_LOGIN_PASSWORD, "Requires isolated fixtures");
    await page.setViewportSize({ width: 390, height: 844 });
    await loginFor(page,"/account/submissions/new");
    await page.getByLabel("사케 일본어 이름 (필수)").fill("ブラウザー検証酒");
    await page.getByLabel("사케 한국어 이름 (필수)").fill("브라우저 검증 제보");
    await page.getByLabel("새 양조장 제안").check();
    await page.getByLabel("양조장 일본어 이름 (필수)").fill("合成醸造");
    await page.getByLabel("양조장 한국어 이름 (필수)").fill("합성 양조장");
    await page.getByText("상품 세부 정보 (선택)",{ exact: true }).click();
    await page.getByLabel("용량 (ml)").fill("720");
    const image = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAFElEQVR4nGMUknNnwAaYsIoOWgkAYt0AhwzkAcIAAAAASUVORK5CYII=","base64");
    await page.getByLabel("이미지 파일").setInputFiles({ name: "synthetic.png", mimeType: "image/png", buffer: image });
    await page.getByRole("button", { name: "이미지 업로드", exact: true }).click();
    await page.getByLabel("이미지 권리 근거 (필수)").fill("직접 만든 합성 이미지");
    await page.getByRole("checkbox", { name: /이미지 이용 근거를 확인/ }).check();
    const preview = page.getByRole("img", { name: "비공개 제보 이미지" });
    await expect.poll(() => preview.evaluate(image => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);

    const keys: string[] = [];
    await page.route("**/api/user/sake-submissions", async route => {
        if (route.request().method() !== "POST") return route.continue();
        keys.push(route.request().headers()["idempotency-key"]);
        if (keys.length === 1) {
            const response = await route.fetch();
            expect(response.status()).toBe(201);
            await route.abort();
        } else await route.continue();
    });
    await page.getByRole("button", { name: "제보 제출", exact: true }).click();
    await expect(page.getByRole("alert").filter({ hasText: "서버에 연결할 수 없습니다" })).toBeVisible();
    await page.getByRole("button", { name: "제보 제출", exact: true }).click();
    await expect(page).toHaveURL(/\/account\/submissions\/[0-9a-f-]{36}$/);
    expect(keys[0]).toBeTruthy();
    expect(keys).toEqual([keys[0],keys[0]]);
    await expect(page.getByRole("heading", { name: "브라우저 검증 제보" })).toBeVisible();
    await expect(page.getByText("720 ml",{ exact: true })).toBeVisible();
    await expect(page.getByRole("img", { name: "비공개 제보 이미지" })).toBeVisible();
    await page.getByRole("link", { name: "내 제보 목록" }).click();
    await expect(page.getByRole("link",{ name: /브라우저 검증 제보/ })).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("member reads a revision request, resubmits and confirms cancellation", async ({ page }) => {
    test.setTimeout(65000);
    test.skip(!process.env.AUTH_TEST_LOGIN_EMAIL || !process.env.AUTH_TEST_LOGIN_PASSWORD, "Requires isolated fixtures");
    await loginFor(page,"/account/submissions/e2e00000-0000-4000-8000-000000000001");
    await expect(page.getByText("사케 이름을 확인해 주세요.")).toBeVisible();
    await page.getByRole("button", { name: "보완하기", exact: true }).click();
    const title = page.getByLabel("사케 한국어 이름 (필수)");
    await expect(title).toHaveValue("보완할 사케");
    await title.fill("보완한 사케");
    await page.getByRole("button", { name: "보완 제출", exact: true }).click();
    await expect(page.getByRole("heading",{ name: "보완한 사케" })).toBeVisible();
    await expect(page.getByText("검토 대기 · 리비전 2")).toBeVisible();
    await expect(page.getByRole("heading",{ name: "이전 리비전" })).toBeVisible();
    await page.getByRole("button",{ name: "제보 취소", exact: true }).click();
    await page.getByLabel("취소 사유 (선택)").fill("브라우저 확인 완료");
    await page.getByRole("button",{ name: "제보 취소 확인" }).click();
    await expect(page.getByText("취소 · 리비전 2")).toBeVisible();
    await expect(page.getByText("브라우저 확인 완료",{ exact: true })).toBeVisible();
    await expect(page.getByRole("button",{ name: "제보 취소", exact: true })).toHaveCount(0);
});

test("approval progress keeps the member status separate and hides edit actions", async ({ page }) => {
    test.skip(!process.env.AUTH_TEST_LOGIN_EMAIL || !process.env.AUTH_TEST_LOGIN_PASSWORD, "Requires isolated fixtures");
    const id = "e2e00000-0000-4000-8000-000000000002";
    const submission = { id, status: "PENDING", version: 0, currentRevision: 1, nameJa: "合成酒", nameKo: "승인 검증",
        approvalInProgress: true, sakeNo: null, createdAt: "2026-01-01T00:00:00", updatedAt: "2026-01-01T00:00:00" };
    await page.route(`**/api/user/sake-submissions/${id}`, route => route.fulfill({ json: { submission, revisions: [], events: [] } }));
    await loginFor(page,`/account/submissions/${id}`);
    await expect(page.getByText("검토 대기 · 리비전 1")).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "이미지 승인 작업이 진행 중입니다." })).toBeVisible();
    await expect(page.getByRole("button",{ name: "제보 취소", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button",{ name: "보완하기", exact: true })).toHaveCount(0);
    await expect(page.getByRole("link",{ name: "공개 사케 보기" })).toHaveCount(0);
    Object.assign(submission,{ status: "APPROVED", approvalInProgress: false, sakeNo: 1 });
    await page.getByRole("button",{ name: "상태 새로고침" }).click();
    await expect(page.getByRole("link",{ name: "공개 사케 보기" })).toHaveAttribute("href","/sake/1");
});
