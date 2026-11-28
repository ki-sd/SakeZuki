"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { useAuth, useSessionGuard } from "@/components/auth/AuthProvider";
import { errorMessage } from "@/app/api/auth";
import { getAccount, getHistory, getLedger, startVerification } from "@/app/api/account";

const date = (epoch: number) => new Date(epoch * 1000).toLocaleString("ko-KR");
const kinds: Record<string, string> = { GRANT: "최초 무료 지급", RESERVE: "추천 예약", COMMIT: "추천 완료", REFUND: "예약 복구" };
export default function AccountPage() {
    const { status, member } = useAuth(); const guard = useSessionGuard(); const router = useRouter();
    const [page, setPage] = useState(0); const [ledgerPage, setLedgerPage] = useState(0);
    const [consent, setConsent] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState("");
    const enabled = status === "authenticated";
    // 회원과 페이지를 모두 키에 넣어 계정 전환 때 다른 사람의 기록이 재사용되지 않게 한다.
    const account = useQuery({ queryKey: ["account", member?.memberNo], queryFn: getAccount, enabled, retry: false });
    const history = useQuery({ queryKey: ["history", member?.memberNo, page], queryFn: () => getHistory(page), enabled, retry: false });
    const ledger = useQuery({ queryKey: ["ledger", member?.memberNo, ledgerPage], queryFn: () => getLedger(ledgerPage), enabled, retry: false });
    useEffect(() => { if (status === "anonymous") router.replace("/login?returnTo=%2Faccount"); }, [status, router]);
    async function verify() {
        if (!account.data || !consent || busy) return;
        setBusy(true); setError("");
        const valid = guard();
        try {
            const challenge = await startVerification(account.data);
            if (!valid()) return;
            sessionStorage.setItem("sakezuki-verification", challenge.requestId);
            window.location.assign(challenge.redirectUrl);
        } catch (error) { if (valid()) setError(errorMessage(error)); }
        finally { setBusy(false); }
    }
    return <><Header /><main id="main-content" className="page-shell space-y-7 py-10 sm:py-14">
        <header><p className="text-sm text-stone-500">회원 정보와 나의 페어링</p><h1 className="mt-3 text-3xl font-semibold">내 계정</h1></header>
        {!enabled ? <p role="status" className="ui-state">로그인 상태를 확인하고 있습니다.</p> : <>
            <section className="ui-panel p-6 sm:p-8" aria-labelledby="account-title">
                <h2 id="account-title" className="text-xl font-semibold">회원 정보 · 크레딧</h2><p className="mt-4 break-all">{member?.email}</p>
                <p className="mt-2 text-sm text-stone-500">{member?.roles.includes("ROLE_ADMIN") ? "관리자" : "일반 회원"}</p>
                {account.isPending ? <p role="status" className="mt-5">회원 정보를 불러오는 중…</p> : account.isError ?
                    <div role="alert" className="mt-5"><p>{errorMessage(account.error)}</p><button className="ui-button-secondary mt-3" onClick={() => account.refetch()}>회원 정보 다시 확인</button></div> : account.data && <>
                        <div className="my-6 rounded-lg bg-stone-50 p-5"><p className="text-2xl font-semibold">잔여 크레딧 {account.data.balance}개</p>
                            <p className="mt-3 text-sm leading-7 text-stone-600">새 추천은 캐시 사용 여부와 관계없이 1크레딧입니다. 최근 {account.data.historyDays}일 기록의 재열람은 무료입니다.</p>
                            {account.data.balance === 0 && <p className="mt-2 text-sm">새 추천을 위한 크레딧이 부족합니다. 아래 이전 기록과 공개 예시는 무료로 볼 수 있습니다.</p>}
                            <p className="mt-2 text-sm text-stone-500">유료 충전은 준비 중입니다.</p></div>
                        <p className="font-medium">{account.data.verified ? "본인확인·성인확인 완료" : "본인확인·성인확인 대기"}</p>
                        {!account.data.verified && (account.data.verificationAvailable ? <div className="mt-4 space-y-3">
                            <p className="text-sm text-stone-600">최초 본인확인 완료 시 {account.data.initialGrant}크레딧을 지급하며 동일인에게 한 번만 지급합니다.</p>
                            <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} />
                                <span><a href={account.data.termsUrl} target="_blank" rel="noopener noreferrer" className="underline">이용약관</a> 및 <a href={account.data.privacyUrl} target="_blank" rel="noopener noreferrer" className="underline">개인정보 처리방침</a>에 동의합니다.</span></label>
                            <button className="ui-button" disabled={!consent || busy} onClick={verify}>본인확인 시작</button>
                        </div> : <div className="mt-3 text-sm leading-7 text-stone-600"><p>본인확인 서비스 연결을 준비 중입니다. 지금은 인증을 완료할 수 없으며, 연결 후 최초 확인 시 {account.data.initialGrant}크레딧을 지급합니다.</p><p>사케 탐색과 무료 공개 예시는 바로 이용할 수 있습니다.</p></div>)}
                    </>}
                <div className="mt-6 flex flex-wrap gap-3"><Link href="/account/submissions" className="ui-button-secondary">내 사케 제보</Link><Link href="/examples" className="ui-button-secondary">무료 페어링 예시 보기</Link><Link href="/recommend" className="ui-button-secondary">AI 추천 이용 안내</Link></div>
            </section>
            <section className="ui-panel p-6 sm:p-8" aria-labelledby="history-title">
                <h2 id="history-title" className="text-xl font-semibold">최근 추천 기록</h2><p className="mt-3 text-sm leading-7 text-stone-600">완료 후 {account.data?.historyDays ?? 30}일 동안 당시 받은 결과를 무료로 다시 볼 수 있습니다. 만료된 기록은 표시되지 않습니다.</p>
                {history.isError && <div role="alert" className="mt-4"><p>{errorMessage(history.error)}</p><button className="ui-button-secondary mt-3" onClick={() => history.refetch()}>기록 다시 불러오기</button></div>}
                {history.isPending ? <p role="status" className="mt-5">기록을 불러오는 중…</p> : history.data?.length === 0 ? <div className="mt-6 rounded-lg bg-stone-50 p-5 text-sm leading-7"><p>열람할 수 있는 추천 기록이 없습니다.</p><Link href="/examples" className="mt-2 inline-block underline">먼저 공개 예시 둘러보기</Link></div> :
                    <ul className="mt-5 divide-y divide-stone-200">{history.data?.map(item => <li key={item.id} className="flex flex-wrap items-center justify-between gap-4 py-5">
                        <div className="min-w-0"><p className="text-xs text-stone-500">{item.kind === "SAKE" ? "음식 → 사케" : "사케 → 음식"}</p><h3 className="mt-2 break-words font-medium">{item.inputLabel || item.inputValue}</h3>
                            <p className="mt-2 text-xs leading-6 text-stone-500">추천 {date(item.completedAtEpoch)}<br />열람 만료 {date(item.expiresAtEpoch)}</p></div>
                        <Link className="ui-button-secondary" href={`/account/recommendations/${item.id}`}>결과 다시 보기</Link>
                    </li>)}</ul>}
                <nav aria-label="추천 기록 페이지" className="mt-5 flex items-center gap-4 text-sm"><button className="ui-button-secondary disabled:opacity-40" disabled={page === 0 || history.isFetching} onClick={() => setPage(page - 1)}>이전 기록</button>
                    <span>{page + 1}페이지</span><button className="ui-button-secondary disabled:opacity-40" disabled={(history.data?.length ?? 0) < 30 || history.isFetching} onClick={() => setPage(page + 1)}>다음 기록</button></nav>
            </section>
            <section className="ui-panel p-6 sm:p-8" aria-labelledby="ledger-title"><h2 id="ledger-title" className="text-xl font-semibold">크레딧 이용 내역</h2>
                <p className="mt-3 text-sm leading-7 text-stone-600">예약 시 1개를 제외하고, 완료 확정은 추가 차감 없이 기록합니다. 실패하거나 만료된 예약은 복구합니다.</p>
                {ledger.isError && <div role="alert" className="mt-4"><p>{errorMessage(ledger.error)}</p><button className="ui-button-secondary mt-3" onClick={() => ledger.refetch()}>이용 내역 다시 확인</button></div>}
                {ledger.isPending && <p role="status" className="mt-4">이용 내역을 불러오는 중…</p>}
                {ledger.data?.length === 0 && <p className="mt-5 text-sm text-stone-500">아직 지급·이용 내역이 없습니다.</p>}
                <ul className="mt-4 divide-y divide-stone-200">{ledger.data?.map(entry => <li key={entry.id} className="flex flex-wrap justify-between gap-3 py-4 text-sm">
                    <div><p>{kinds[entry.kind] ?? "크레딧 거래"}</p><p className="mt-1 text-xs text-stone-500">{date(entry.createdAtEpoch)}</p></div><span>{entry.delta > 0 ? "+" : ""}{entry.delta} · 잔액 {entry.balanceAfter}</span>
                </li>)}</ul>
                <nav aria-label="크레딧 내역 페이지" className="mt-5 flex items-center gap-4 text-sm"><button className="ui-button-secondary disabled:opacity-40" disabled={ledgerPage === 0 || ledger.isFetching} onClick={() => setLedgerPage(ledgerPage - 1)}>이전 내역</button>
                    <span>{ledgerPage + 1}페이지</span><button className="ui-button-secondary disabled:opacity-40" disabled={(ledger.data?.length ?? 0) < 50 || ledger.isFetching} onClick={() => setLedgerPage(ledgerPage + 1)}>다음 내역</button></nav>
            </section>
        </>}
        {error && <p role="alert" className="text-red-700">{error}</p>}
    </main></>;
}
