"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { submissionsPath, type Status } from "@/app/api/submissions";
import { useAuth } from "@/components/auth/AuthProvider";
import Header from "@/components/layout/Header";

export const statusLabels: Record<Status, string> = {
    PENDING: "검토 대기", NEEDS_REVISION: "보완 요청", REJECTED: "반려", CANCELLED: "취소", APPROVED: "승인",
};
export const submissionDate = (value: string) => new Date(`${value}Z`).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" });

export default function SubmissionShell({ title, children }: { title: string; children: ReactNode }) {
    const { status, member } = useAuth();
    const pathname = usePathname();
    const router = useRouter();
    useEffect(() => {
        if (status === "anonymous") router.replace(`/login?returnTo=${encodeURIComponent(pathname)}`);
    }, [pathname, router, status]);
    const top = pathname === submissionsPath;
    return <><Header /><main id="main-content" className="page-shell space-y-6 py-8 sm:py-12">
        <header className="flex flex-wrap items-end justify-between gap-4">
            <div>
                <Link className="text-sm text-stone-600 underline underline-offset-4" href={top ? "/account" : submissionsPath}>
                    {top ? "내 계정" : "내 제보 목록"}
                </Link>
                <h1 className="mt-3 text-3xl font-semibold">{title}</h1>
            </div>
            {top && member && <Link className="ui-button" href={`${submissionsPath}/new`}>새 사케 제보</Link>}
        </header>
        {status === "authenticated" && member ? <div key={member.memberNo}>{children}</div> :
            <p role="status" className="ui-state">{status === "loading" ? "로그인 상태를 확인하고 있습니다." : "로그인 페이지로 이동합니다."}</p>}
    </main></>;
}
