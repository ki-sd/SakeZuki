"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useSessionGuard } from "@/components/auth/AuthProvider";
import Header from "@/components/layout/Header";
import { errorMessage, signupInfo, signupPolicy, socialSignup } from "@/app/api/auth";
import { formatPhone } from "@/commons/phone";

export default function SocialSignup() {
    const guard = useSessionGuard();
    const router = useRouter();
    const info = useQuery({ queryKey: ["social-signup-info"], queryFn: signupInfo, retry: false, staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false });
    const policy = useQuery({ queryKey: ["signup-policy"], queryFn: signupPolicy, retry: false });
    const [consent, setConsent] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [phone, setPhone] = useState("");
    async function submit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (busy || error || !consent || !policy.data?.enabled) return;
        const data = new FormData(event.currentTarget);
        setBusy(true);
        const operation = socialSignup({ address1: String(data.get("address1")).trim(), address2: String(data.get("address2")).trim(), phone: String(data.get("phone")).trim(), termsVersion: policy.data.termsVersion, privacyVersion: policy.data.privacyVersion });
        const valid = guard();
        try {
            await operation;
            if (valid()) router.replace("/account");
        } catch (error) { if (valid()) setError(errorMessage(error)); }
        finally { if (valid()) setBusy(false); }
    }
    return <><Header /><main id="main-content" className="page-shell py-12">
        <section className="ui-panel mx-auto max-w-lg p-6 sm:p-9">
            <h1 className="text-3xl font-semibold">가입을 마무리해 주세요</h1>
            <p className="mt-3 text-sm text-stone-500">Google 계정에 연락처와 주소를 추가합니다. 정식 회원 전환과 무료 크레딧 지급에는 약관 동의 및 본인확인·성인확인이 필요합니다.</p>
            {info.isPending ? <p className="mt-8" role="status">가입 정보를 확인하고 있습니다…</p> : info.isError ?
                <div className="mt-8"><p role="alert">{errorMessage(info.error)}</p><Link href="/login" className="mt-5 inline-block underline">Google 로그인 다시 시작</Link></div> :
                policy.isPending ? <p className="mt-8" role="status">가입 정책을 확인하고 있습니다.</p> : policy.isError || !policy.data?.enabled ?
                    <p className="mt-8" role="alert">회원가입 약관을 준비 중입니다. 기존 계정 로그인과 사케 탐색은 이용할 수 있습니다.</p> :
                <form onSubmit={submit} className="mt-8 flex flex-col gap-5">
                    <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} required />
                        <span><a className="underline" href={policy.data.termsUrl} target="_blank" rel="noopener noreferrer">이용약관</a> 및 <a className="underline" href={policy.data.privacyUrl} target="_blank" rel="noopener noreferrer">개인정보 처리방침</a>에 동의합니다.</span></label>
                    <label className="flex flex-col gap-2 text-sm">이름<input className="ui-input" value={info.data.name} readOnly autoComplete="name" /></label>
                    <label className="flex flex-col gap-2 text-sm">이메일<input className="ui-input" value={info.data.email} readOnly autoComplete="email" /></label>
                    <label className="flex flex-col gap-2 text-sm">주소<input className="ui-input" name="address1" required maxLength={1000} autoComplete="address-line1" disabled={busy || !!error} /></label>
                    <label className="flex flex-col gap-2 text-sm">상세주소 (선택)<input className="ui-input" name="address2" maxLength={1000} autoComplete="address-line2" disabled={busy || !!error} /></label>
                    <label className="flex flex-col gap-2 text-sm">전화번호<input className="ui-input" name="phone" type="tel" inputMode="numeric"
                        value={phone} onChange={event => setPhone(formatPhone(event.target.value))}
                        required pattern="[0-9]{3}-[0-9]{4}-[0-9]{4}" maxLength={13} placeholder="010-1234-5678"
                        autoComplete="tel-national" disabled={busy || !!error} /></label>
                    {error && <div role="alert" className="text-sm text-red-700"><p>{error}</p><Link href="/login" className="mt-3 inline-block underline">정보를 확인한 뒤 Google 로그인 다시 시작</Link></div>}
                    <button className="ui-button" disabled={!consent || busy || !!error}>{busy ? "계정 생성 중…" : "계정 생성 후 본인확인"}</button>
                </form>}
        </section>
    </main></>;
}
