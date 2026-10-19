"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import Header from "@/components/layout/Header";
import { useAuth } from "@/components/auth/AuthProvider";

export default function AccountPage() {
    const { status, member } = useAuth();
    const router = useRouter();
    useEffect(() => { if (status === "anonymous") router.replace("/login?returnTo=%2Faccount"); }, [status, router]);
    return <><Header /><main className="page-shell py-12">
        {member ? <section className="ui-panel max-w-lg p-8"><h1 className="text-2xl font-semibold">내 계정</h1>
            <p className="mt-6 break-all">{member.email}</p><p className="mt-3 text-sm text-stone-500">{member.roles.includes("ROLE_ADMIN") ? "관리자" : "일반 회원"}</p>
        </section> : <p role="status">로그인 상태를 확인하고 있습니다…</p>}
    </main></>;
}
