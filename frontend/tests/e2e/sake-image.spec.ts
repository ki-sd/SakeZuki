import { expect, test } from "@playwright/test";

const pixel = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=", "base64");
const fallback = "/images/sake-placeholder.png";

test("detail image switches between current URLs and fallback on the same mounted page", async ({page}) => {
    const base = "/api/images/sakes/42/";
    const name = "합성 사케 이미지";
    let imageUrl: string | null = base + "initial";
    await page.route("**/api/sake/42", route => route.fulfill({json: {no: 42, nameJa: name, imageUrl}}));
    await page.route("**/api/images/sakes/42/*", route => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith("/missing")) return route.fulfill({status: 404});
        if (path.endsWith("/unavailable")) return route.fulfill({status: 503});
        return route.fulfill({contentType: "image/png", body: pixel});
    });
    await page.route("**" + fallback, route => route.fulfill({contentType: "image/png", body: pixel}));
    await page.goto("/sake/42");
    const image = page.getByRole("img", {name, exact: true});
    const sourcePath = () => image.evaluate((img: HTMLImageElement) => new URL(img.src).pathname);
    await expect.poll(sourcePath).toBe(base + "initial");
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);
    const retired = await image.elementHandle();

    async function update(url: string | null) {
        imageUrl = url;
        const refetch = page.waitForResponse(response => new URL(response.url()).pathname === "/api/sake/42");
        await page.evaluate(() => {
            // TanStack Query refetches on visibility restoration.
            Object.defineProperty(document, "visibilityState", {configurable: true, value: "hidden"});
            window.dispatchEvent(new Event("visibilitychange"));
            Reflect.deleteProperty(document, "visibilityState");
            window.dispatchEvent(new Event("visibilitychange"));
        });
        await refetch;
    }

    await update(base + "missing");
    await expect.poll(sourcePath).toBe(fallback);
    await update(base + "replacement");
    await expect.poll(sourcePath).toBe(base + "replacement");
    await retired?.dispatchEvent("error");
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);
    await expect.poll(sourcePath).toBe(base + "replacement");

    await update(base + "unavailable");
    await expect.poll(sourcePath).toBe(fallback);
    await update(base + "last");
    await expect.poll(sourcePath).toBe(base + "last");
    await update(null);
    await expect.poll(sourcePath).toBe(fallback);
});

test("detail placeholder failure does not restart image requests", async ({page}) => {
    await page.route("**/api/sake/42", route => route.fulfill({json: {no: 42, nameJa: "합성 이미지", imageUrl: "/api/images/sakes/42/broken"}}));
    await page.route("**/api/images/sakes/42/broken", route => route.fulfill({status: 404}));
    let requests = 0;
    await page.route("**" + fallback, route => { requests++; return route.fulfill({status: 503}); });
    await page.goto("/sake/42");
    const image = page.getByRole("img", {name: "합성 이미지", exact: true});
    await expect(image).toHaveAttribute("src", fallback);
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete)).toBe(true);
    await image.dispatchEvent("error");
    await expect(image).toHaveAttribute("src", fallback);
    expect(requests).toBe(1);
});
