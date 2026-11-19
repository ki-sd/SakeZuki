"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { useAuth } from "@/components/auth/AuthProvider";
import { errorMessage } from "@/app/api/auth";
import { getAccount, getHistory, getHistoryDetail, getLedger, startVerification } from "@/app/api/account";
import type { FoodRecommendResponse, SakeRecommendResponse } from "@/types/recommend";

type Snapshot = FoodRecommendResponse | SakeRecommendResponse;
const date = (epoch: number) => new Date(epoch * 1000).toLocaleString("ko-KR");
const kinds: Record<string, string> = { GRANT: "최초 지급", RESERVE: "추천 예약", COMMIT: "추천 완료", REFUND: "예약 복구" };

export default function AccountPage() {
    const { status, member } = useAuth();
    const router = useRouter();
    const [page, setPage] = useState(0);
    const [consent, setConsent] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [opened, setOpened] = useState<{ owner: number | undefined; data: Snapshot } | null>(null);
    const snapshot = opened?.owner === member?.memberNo ? opened?.data : null;
    const setSnapshot = (value: Snapshot | null) => setOpened(value ? { owner: member?.memberNo, data: value } : null);
    const enabled = status === "authenticated";
    // 계정별 쿼리 키로 다른 계정의 잔액·기록이 화면에 재사용되지 않게 한다.
    const account = useQuery({ queryKey: ["account", member?.memberNo], queryFn: getAccount, enabled, retry: false });
    const history = useQuery({ queryKey: ["history", member?.memberNo, page], queryFn: () => getHistory(page), enabled, retry: false });
    const ledger = useQuery({ queryKey: ["ledger", member?.memberNo], queryFn: () => getLedger(0), enabled, retry: false });
    useEffect(() => { if (status === "anonymous") router.replace("/login?returnTo=%2Faccount"); }, [status, router]);
    async function verify() {
        if (!account.data || !consent || busy) return;
        setBusy(true); setError("");
        try {
            const challenge = await startVerification(account.data);
            sessionStorage.setItem("sakezuki-verification", challenge.requestId);
            window.location.assign(challenge.redirectUrl);
        } catch (error) { setError(errorMessage(error)); setBusy(false); }
    }
    async function open(id: string) {
        setSnapshot(null); setError("");
        try { setSnapshot(await getHistoryDetail(id)); } catch (error) { setError(errorMessage(error)); }
    }
    return <><Header /><main className="page-shell space-y-7 py-10">
        <h1 className="text-3xl font-semibold">내 계정</h1>
        {!enabled ? <p role="status">로그인 상태를 확인하고 있습니다.</p> : <>
            <section className="ui-panel p-6">
                <p className="break-all">{member?.email}</p>
                <p className="mt-2 text-sm text-stone-500">{member?.roles.includes("ROLE_ADMIN") ? "관리자" : "일반 회원"}</p>
                {account.isPending ? <p role="status">회원 정보를 불러오는 중…</p> : account.isError ?
                    <p role="alert">{errorMessage(account.error)}</p> : account.data && <>
                        <p className="mt-4 text-xl font-semibold">잔여 크레딧 {account.data.balance}개</p>
                        <p className="mt-2 text-sm text-stone-600">추천 1회는 캐시 사용 여부와 관계없이 1크레딧입니다. 기록 재열람은 무료입니다.</p>
                        <p className="mt-2 text-sm text-stone-600">유료 충전은 준비 중입니다.</p>
                        <p className="mt-4">{account.data.verified ? "본인확인·성인확인 완료" : "가입 완료를 위한 본인확인이 필요합니다."}</p>
                        {!account.data.verified && (account.data.verificationAvailable ? <div className="mt-4 space-y-3">
                            <label className="flex items-center gap-2"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} />
                                <span><a href={account.data.termsUrl} target="_blank" rel="noopener noreferrer" className="underline">이용약관</a> 및 <a href={account.data.privacyUrl} target="_blank" rel="noopener noreferrer" className="underline">개인정보 처리방침</a>에 동의합니다.</span>
                            </label>
                            <button className="ui-button" disabled={!consent || busy} onClick={verify}>본인확인 시작</button>
                        </div> : <p className="mt-3 text-sm text-stone-600">본인확인 서비스 연결을 준비 중입니다. 연결 후 최초 확인 완료 시 {account.data.initialGrant}크레딧을 지급합니다. 사케 탐색은 계속 이용할 수 있습니다.</p>)}
                    </>}
                <Link href="/recommend" className="mt-4 inline-block underline">AI 추천으로 이동</Link>
            </section>
            <section className="ui-panel p-6">
                <h2 className="text-xl font-semibold">최근 추천 기록</h2>
                <p className="mt-2 text-sm text-stone-600">완료 후 {account.data?.historyDays ?? 30}일 동안 당시 받은 결과를 무료로 다시 볼 수 있습니다.</p>
                {history.isError && <p role="alert">{errorMessage(history.error)}</p>}
                {history.isPending ? <p role="status">기록을 불러오는 중…</p> : history.data?.length === 0 ? <p className="mt-4">열람할 수 있는 추천 기록이 없습니다.</p> :
                    <ul className="mt-4 divide-y divide-stone-200">{history.data?.map(item => <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 py-4">
                        <div><p>{item.kind === "SAKE" ? "음식 → 사케 · " + item.inputValue : "사케 → 음식 · 제품 #" + item.inputValue}</p>
                            <p className="text-sm text-stone-500">{date(item.completedAtEpoch)} · 만료 {date(item.expiresAtEpoch)}</p></div>
                        <button className="ui-button" onClick={() => open(item.id)}>결과 다시 보기</button>
                    </li>)}</ul>}
                <div className="mt-4 flex gap-3"><button disabled={page === 0} onClick={() => { setSnapshot(null); setPage(page - 1); }}>이전</button>
                    <span>{page + 1}페이지</span><button disabled={(history.data?.length ?? 0) < 30} onClick={() => { setSnapshot(null); setPage(page + 1); }}>다음</button></div>
                {snapshot && <div className="mt-6 rounded-xl bg-stone-50 p-5"><h3 className="font-semibold">당시 제공된 추천 결과</h3>
                    {"recommendations" in snapshot ? <ul>{snapshot.recommendations.map(item => <li key={item.no} className="mt-4">
                        <p className="font-medium">{item.nameKo || item.nameJa}</p><p className="mt-1 text-sm">{item.reason}</p></li>)}</ul> :
                        <ul>{snapshot.recommends.map((item, index) => <li key={index} className="mt-4"><p className="font-medium">{item.name}</p><p className="mt-1 text-sm">{item.reason}</p></li>)}</ul>}
                </div>}
            </section>
            <section className="ui-panel p-6"><h2 className="text-xl font-semibold">최근 크레딧 거래</h2>
                {ledger.isError && <p role="alert">{errorMessage(ledger.error)}</p>}
                <ul className="mt-3 divide-y divide-stone-200">{ledger.data?.map(entry => <li key={entry.id} className="flex justify-between gap-3 py-3 text-sm">
                    <span>{kinds[entry.kind] ?? entry.kind} · {date(entry.createdAtEpoch)}</span><span>{entry.delta > 0 ? "+" : ""}{entry.delta} · 잔액 {entry.balanceAfter}</span>
                </li>)}</ul>
            </section>
        </>}
        {error && <p role="alert" className="text-red-700">{error}</p>}
    </main></>;
}
