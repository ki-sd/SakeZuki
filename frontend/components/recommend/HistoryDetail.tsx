"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { useAuth } from "@/components/auth/AuthProvider";
import { errorMessage } from "@/app/api/auth";
import { getAccount, getHistoryReceipt } from "@/app/api/account";
import { snapshotView } from "@/types/pairing";
import PairingResult from "./PairingResult";

export default function HistoryDetail({ id }: { id: string }) {
    const auth = useAuth(); const router = useRouter();
    const account = useQuery({ queryKey: ["account", auth.member?.memberNo], queryFn: getAccount, enabled: auth.status === "authenticated", retry: false });
    const query = useQuery({ queryKey: ["history-receipt", auth.member?.memberNo, id], queryFn: () => getHistoryReceipt(id), enabled: auth.status === "authenticated", retry: false, staleTime: 0, gcTime: 0 });
    useEffect(() => { if (auth.status === "anonymous") router.replace(`/login?returnTo=${encodeURIComponent(`/account/recommendations/${id}`)}`); }, [auth.status, id, router]);
    const result = auth.status === "authenticated" && query.data ? snapshotView(query.data.result) : null;
    return <><Header /><main id="main-content" className="page-shell space-y-7 py-10">
        <Link href="/account" className="text-sm underline underline-offset-4">마이페이지로 돌아가기</Link>
        <header><p className="text-sm text-stone-500">무료 재열람</p><h1 className="mt-2 text-3xl font-semibold">당시 제공된 추천 결과</h1>
            {account.data && auth.status === "authenticated" && <p className="mt-3 text-sm font-medium">잔여 크레딧 {account.data.balance}개</p>}
            <p className="mt-4 text-sm leading-7 text-stone-600">저장된 개인 스냅샷을 보여줍니다. 이 화면에서는 새 추천을 요청하거나 크레딧을 사용하지 않습니다.</p>
            {query.data && auth.status === "authenticated" && <p className="mt-3 text-sm text-stone-500">추천 {new Date(query.data.completedAt * 1000).toLocaleString("ko-KR")} · 열람 만료 {new Date(query.data.expiresAt * 1000).toLocaleString("ko-KR")}</p>}</header>
        {query.isError ? <div role="alert" className="ui-state"><p>{errorMessage(query.error)}</p><p className="mt-3">기록이 만료되었거나 현재 계정으로 볼 수 없는 경우 마이페이지에서 유효한 기록을 확인해 주세요.</p><button className="ui-button-secondary mt-4" onClick={() => query.refetch()}>기록 다시 확인</button></div> : !result ? <p role="status" className="ui-state">추천 기록을 확인하고 있습니다…</p> : <PairingResult result={result} />}
        <div className="flex flex-wrap gap-3"><Link className="ui-button-secondary" href="/account">내 추천 기록</Link><Link className="ui-button-secondary" href="/examples">무료 공개 예시</Link></div>
    </main></>;
}
