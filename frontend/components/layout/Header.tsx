"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";
import {useState} from "react";
import {useQueryClient} from "@tanstack/react-query";
import {useAuth} from "@/components/auth/AuthProvider";
import {errorMessage, logout} from "@/app/api/auth";

export default function Header (){
    // usePathname은 현재 브라우저 경로를 읽어 메뉴의 활성 상태를 바꾼다.
    // 이 값과 링크의 강조가 이동 직후 다시 렌더링되어야 하므로 Client Component다.
    const pathname=usePathname();
    const {status, member}=useAuth();
    const queryClient=useQueryClient();
    const [busy,setBusy]=useState(false);
    const [error,setError]=useState("");
    async function signOut() {
        setBusy(true); setError("");
        try { await logout(); queryClient.clear(); }
        catch(error) { setError(errorMessage(error)); }
        finally { setBusy(false); }
    }
    return (
        <header className={"border-b border-stone-200 bg-white"}>
            <div className={"page-shell flex min-h-16 flex-wrap items-center justify-between gap-3 py-2"}>
                <Link href={"/sake"} className={"shrink-0 rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
                    <img src={"/images/logo.png"} alt={"SakeZuki"} className={"h-10 w-auto object-contain sm:h-11"}/>
                </Link>

                <nav aria-label={"주 메뉴"} className={"flex h-full items-center gap-4 text-sm font-medium sm:gap-7"}>
                    <Link href={"/sake"} aria-current={pathname.startsWith("/sake") ? "page" : undefined}
                          className={`flex h-full items-center border-b-2 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${pathname.startsWith("/sake") ? "border-stone-800 text-stone-900" : "border-transparent text-stone-500 hover:text-stone-900"}`}>
                        사케 목록
                    </Link>
                    <Link href={"/recommend"} aria-current={pathname.startsWith("/recommend") ? "page" : undefined}
                          className={`flex h-full items-center border-b-2 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${pathname.startsWith("/recommend") ? "border-stone-800 text-stone-900" : "border-transparent text-stone-500 hover:text-stone-900"}`}>
                        페어링
                    </Link>
                    {status === "loading" ? <span className="text-xs text-stone-500" role="status">확인 중…</span> : member ? <>
                        <Link href="/account" className="text-stone-600 hover:text-stone-900">{member.roles.includes("ROLE_ADMIN") ? "관리자 계정" : "내 계정"}</Link>
                        <button type="button" className="cursor-pointer text-stone-500 disabled:opacity-50" onClick={signOut} disabled={busy}>{busy ? "로그아웃 중…" : "로그아웃"}</button>
                    </> : <Link href={`/login?returnTo=${encodeURIComponent(pathname)}`} className="text-stone-600 hover:text-stone-900">로그인</Link>}
                </nav>
            </div>
            {error && <p role="alert" className="page-shell pb-3 text-sm text-red-700">{error}</p>}
        </header>
    )
}
