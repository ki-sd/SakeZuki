"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { errorMessage, finishDestination, signupInfo, socialSignup } from "@/app/api/auth";

export default function SocialSignup() {
    const router = useRouter();
    const info = useQuery({ queryKey: ["social-signup-info"], queryFn: signupInfo, retry: false, staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false });
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    async function submit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (busy || error) return;
        const data = new FormData(event.currentTarget);
        setBusy(true);
        try {
            await socialSignup({ address1: String(data.get("address1")).trim(), address2: String(data.get("address2")).trim(), phone: String(data.get("phone")).trim() });
            router.replace(finishDestination());
        } catch (error) { setError(errorMessage(error)); }
        finally { setBusy(false); }
    }
    return <><Header /><main className="page-shell py-12">
        <section className="ui-panel mx-auto max-w-lg p-6 sm:p-9">
            <h1 className="text-3xl font-semibold">가입을 마무리해 주세요</h1>
            <p className="mt-3 text-sm text-stone-500">Google에서 확인한 정보에 연락처와 주소를 추가합니다.</p>
            {info.isPending ? <p className="mt-8" role="status">가입 정보를 확인하고 있습니다…</p> : info.isError ?
                <div className="mt-8"><p role="alert">{errorMessage(info.error)}</p><Link href="/login" className="mt-5 inline-block underline">Google 로그인 다시 시작</Link></div> :
                <form onSubmit={submit} className="mt-8 flex flex-col gap-5">
                    <label className="flex flex-col gap-2 text-sm">이름<input className="ui-input" value={info.data.name} readOnly autoComplete="name" /></label>
                    <label className="flex flex-col gap-2 text-sm">이메일<input className="ui-input" value={info.data.email} readOnly autoComplete="email" /></label>
                    <label className="flex flex-col gap-2 text-sm">주소<input className="ui-input" name="address1" required maxLength={1000} autoComplete="address-line1" disabled={busy || !!error} /></label>
                    <label className="flex flex-col gap-2 text-sm">상세주소 (선택)<input className="ui-input" name="address2" maxLength={1000} autoComplete="address-line2" disabled={busy || !!error} /></label>
                    <label className="flex flex-col gap-2 text-sm">전화번호<input className="ui-input" name="phone" type="tel" required pattern="[0-9+() \-]{7,20}" maxLength={20} autoComplete="tel" disabled={busy || !!error} /></label>
                    {error && <div role="alert" className="text-sm text-red-700"><p>{error}</p><Link href="/login" className="mt-3 inline-block underline">정보를 확인한 뒤 Google 로그인 다시 시작</Link></div>}
                    <button className="ui-button" disabled={busy || !!error}>{busy ? "가입 중…" : "가입하고 시작하기"}</button>
                </form>}
        </section>
    </main></>;
}
