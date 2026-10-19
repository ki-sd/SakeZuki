"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { errorMessage, login, safeReturnTo } from "@/app/api/auth";

export default function LoginForm({ returnTo, oauthError }: { returnTo: string | null; oauthError: boolean }) {
    const router = useRouter();
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(oauthError ? "Google 로그인이 취소되었거나 실패했습니다. 다시 시도해 주세요." : "");
    async function submit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (busy) return;
        const data = new FormData(event.currentTarget);
        setBusy(true); setError("");
        try {
            await login(String(data.get("email")).trim(), String(data.get("password")));
            router.replace(safeReturnTo(returnTo));
        } catch (error) { setError(errorMessage(error)); }
        finally { setBusy(false); }
    }
    function google() {
        sessionStorage.setItem("authReturnTo", safeReturnTo(returnTo));
        // This route belongs to Spring Security and requires a full browser navigation.
        window.location.assign(new URL("/oauth2/authorization/google", window.location.origin).href);
    }
    return <><Header /><main className="page-shell py-12 sm:py-20">
        <section className="ui-panel mx-auto max-w-md p-6 sm:p-9" aria-labelledby="login-title">
            <p className="text-xs font-medium tracking-widest text-stone-500">SAKEZUKI</p>
            <h1 id="login-title" className="mt-3 text-3xl font-semibold">로그인</h1>
            <p className="mt-3 text-sm text-stone-500">다시 만나 반갑습니다.</p>
            <form onSubmit={submit} className="mt-8 flex flex-col gap-5">
                <label className="flex flex-col gap-2 text-sm">이메일<input className="ui-input" name="email" type="email" autoComplete="username" maxLength={255} required disabled={busy} /></label>
                <label className="flex flex-col gap-2 text-sm">비밀번호<input className="ui-input" name="password" type="password" autoComplete="current-password" maxLength={200} required disabled={busy} /></label>
                {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
                <button className="ui-button" disabled={busy}>{busy ? "로그인 중…" : "이메일로 로그인"}</button>
            </form>
            <div className="my-6 border-t border-stone-200" />
            <button type="button" onClick={google} disabled={busy} className="ui-button-secondary w-full">Google로 계속하기</button>
            <p className="mt-4 text-xs leading-6 text-stone-500">처음 이용하신다면 Google 인증 후 추가 정보를 입력해 가입할 수 있습니다. 기존 이메일 계정은 자동으로 연결되지 않습니다.</p>
            <Link href="/sake" className="mt-6 inline-block text-sm underline underline-offset-4">사케 둘러보기</Link>
        </section>
    </main></>;
}
