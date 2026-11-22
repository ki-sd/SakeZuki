"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Header from "@/components/layout/Header";
import PairingResult, { ProductFacts } from "@/components/recommend/PairingResult";
import SakeImage from "@/components/sake/SakeImage";
import { useAuth } from "@/components/auth/AuthProvider";
import { errorMessage } from "@/app/api/auth";
import { getAccount, type History } from "@/app/api/account";
import { prepareRecommendation, requestNewRecommendation } from "@/app/api/recommendation-flow";
import { getSakeDetail, searchSake } from "@/app/api/sake";
import { snapshotView, type RecommendationInput, type Snapshot } from "@/types/pairing";
import type { SakeSearchItem } from "@/types/sake";

export default function RecommendPage() {
    return <Suspense fallback={<><Header /><main id="main-content" className="page-shell py-10"><p role="status">추천 화면을 불러오는 중…</p></main></>}><RecommendContent /></Suspense>;
}
function RecommendContent() {
    const params = useSearchParams(); const auth = useAuth(); const queryClient = useQueryClient();
    const initialNo = Number(params.get("sakeNo"));
    const validNo = Number.isSafeInteger(initialNo) && initialNo > 0;
    const [mode, setMode] = useState<"SAKE" | "FOOD">(validNo ? "FOOD" : "SAKE");
    const [food, setFood] = useState(params.get("food") ?? "");
    const [search, setSearch] = useState("");
    const [selected, setSelected] = useState<SakeSearchItem | null>(null);
    const [busy, setBusy] = useState(false); const busyRef = useRef(false);
    const [error, setError] = useState("");
    const [completed, setCompleted] = useState<{ owner: number | undefined; result: Snapshot } | null>(null);
    const [duplicate, setDuplicate] = useState<{ previous: History; condition: RecommendationInput; owner: number | undefined } | null>(null);
    const notice = useRef<HTMLElement>(null);
    const account = useQuery({ queryKey: ["account", auth.member?.memberNo], queryFn: getAccount, enabled: auth.status === "authenticated", retry: false });
    const initial = useQuery({ queryKey: ["sakeDetail", initialNo], queryFn: () => getSakeDetail(initialNo), enabled: validNo, retry: false });
    const initialSake: SakeSearchItem | null = initial.data ? { no: initial.data.no, nameKo: initial.data.nameKo, nameJa: initial.data.nameJa, imageUrl: initial.data.imageUrl } : null;
    const current = selected ?? initialSake;
    const selectedDetail = useQuery({ queryKey: ["sakeDetail", current?.no], queryFn: () => getSakeDetail(current!.no), enabled: mode === "FOOD" && !!current, retry: false, staleTime: 60000 });
    const searchQuery = useQuery({ queryKey: ["sakeSearch", search], queryFn: () => searchSake(search.trim()), enabled: mode === "FOOD" && search.trim().length >= 2 && !selected && !validNo, staleTime: 30000, retry: false });
    const allowed = auth.status === "authenticated" && !!account.data?.verified;
    const currentDuplicate = duplicate?.owner === auth.member?.memberNo ? duplicate : null;
    const result = completed?.owner === auth.member?.memberNo ? completed?.result : null;
    useEffect(() => { if (currentDuplicate) notice.current?.focus(); }, [currentDuplicate]);
    function clearResult() { setDuplicate(null); setCompleted(null); setError(""); }
    async function execute(input: RecommendationInput, confirmed: boolean) {
        // React의 상태 반영 전 연속 클릭도 막는다. 실제 차감·인증 검증은 서버가 매번 수행한다.
        if (busyRef.current || !allowed) return;
        busyRef.current = true; setBusy(true); setError(""); setCompleted(null); setDuplicate(null);
        const owner = auth.member?.memberNo;
        try {
            if (confirmed) setCompleted({ owner, result: await requestNewRecommendation(input) });
            else {
                const decision = await prepareRecommendation(input);
                if (decision.type === "duplicate") setDuplicate({ ...decision, owner });
                else setCompleted({ owner, result: decision.result });
            }
        } catch (error) { setError(errorMessage(error)); }
        finally {
            busyRef.current = false; setBusy(false);
            await queryClient.invalidateQueries({ queryKey: ["account", owner] });
            await queryClient.invalidateQueries({ queryKey: ["history", owner] });
        }
    }
    function submit(event: React.FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (mode === "SAKE" && food.trim()) void execute({ kind: "SAKE", food: food.trim() }, false);
        else if (mode === "FOOD" && current) void execute({ kind: "FOOD", sakeNo: current.no }, false);
    }
    return <><Header /><main id="main-content" className="page-shell space-y-8 py-10 sm:py-14">
        <header className="max-w-2xl"><p className="text-sm font-medium text-stone-500">음식에서 사케로, 사케에서 음식으로</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight">사케 페어링</h1>
            <p className="mt-4 text-sm leading-7 text-stone-600">새 요청은 공용 캐시 사용 여부와 관계없이 1크레딧입니다. 이전 결과와 같을 수 있으며, 이미 받은 기록의 재열람은 무료입니다.</p></header>
        <aside className="ui-panel p-5 text-sm leading-7" aria-label="추천 이용 상태">
            {auth.status === "loading" || (auth.status === "authenticated" && account.isPending) ? <p role="status">회원 이용 상태를 확인하고 있습니다…</p> :
                account.isError && auth.status === "authenticated" ? <p role="alert">회원 정보를 확인하지 못했습니다. {errorMessage(account.error)}</p> :
                allowed ? <><p className="font-semibold">잔여 크레딧 {account.data?.balance}개</p>{account.data?.balance === 0 && <p>새 추천을 위한 크레딧이 부족합니다. 이전 기록은 무료로 볼 수 있으며 유료 충전은 준비 중입니다.</p>}</> :
                    <><p className="font-semibold">AI 추천 기능은 정식 서비스 준비 중입니다.</p><p>현재는 추천 예시를 무료로 확인하실 수 있습니다. 본인확인이 필요한 회원 기능은 마이페이지에서 안내합니다.</p></>}
            <div className="mt-3 flex flex-wrap gap-x-5 gap-y-2"><Link className="underline underline-offset-4" href="/examples">무료 페어링 예시 보기</Link><Link className="underline underline-offset-4" href="/account">내 계정 · 크레딧 · 추천 기록</Link></div>
        </aside>
        <section className="ui-panel p-5 sm:p-7" aria-label="추천 조건">
            <div className="mb-7 flex flex-wrap gap-2" aria-label="추천 방향">
                {([["SAKE", "음식으로 사케 찾기"], ["FOOD", "사케로 음식 찾기"]] as const).map(([value,label]) => <button key={value} type="button" disabled={busy} aria-pressed={mode === value} onClick={() => { clearResult(); setMode(value); }}
                    className={mode === value ? "ui-button" : "ui-button-secondary"}>{label}</button>)}
            </div>
            <form onSubmit={submit} className="space-y-5">
                {mode === "SAKE" ? <label className="flex flex-col gap-2 text-sm font-medium">음식<input className="ui-input" value={food} maxLength={200} disabled={busy} placeholder="예: 삼겹살, 스키야키, 회"
                    onChange={e => { clearResult(); setFood(e.target.value); }} required /></label> :
                    <div className="space-y-3"><label className="flex flex-col gap-2 text-sm font-medium">사케 이름<input className="ui-input" value={selected ? selected.nameKo || selected.nameJa : validNo ? initialSake?.nameKo || initialSake?.nameJa || "" : search}
                        onChange={e => { clearResult(); setSelected(null); setSearch(e.target.value); }} disabled={busy || validNo} placeholder="두 글자 이상 입력해 주세요" /></label>
                        {validNo && initial.isPending && <p role="status" className="text-sm">선택한 제품을 확인하고 있습니다…</p>}
                        {initial.isError && validNo && <p role="alert" className="text-sm text-red-700">{errorMessage(initial.error)}</p>}
                        {searchQuery.isFetching && <p role="status" className="text-sm">사케를 찾고 있습니다…</p>}
                        {searchQuery.isError && <p role="alert" className="text-sm text-red-700">사케 검색에 실패했습니다. 잠시 후 다시 입력해 주세요.</p>}
                        {!selected && !validNo && search.trim().length >= 2 && searchQuery.data && <ul className="max-h-64 overflow-auto rounded-md border border-stone-200">
                            {searchQuery.data.map(item => <li key={item.no}><button type="button" disabled={busy} className="w-full min-h-11 px-4 py-3 text-left text-sm hover:bg-stone-50 focus-visible:outline-2 focus-visible:outline-stone-700" onClick={() => { clearResult(); setSelected(item); }}>{item.nameKo || item.nameJa}</button></li>)}
                            {!searchQuery.data.length && <li className="p-4 text-sm text-stone-500">검색 결과가 없습니다.</li>}</ul>}
                        {current && <div className="flex gap-4 rounded-md bg-stone-50 p-4"><div className="relative h-24 w-16 shrink-0"><SakeImage src={current.imageUrl} alt={current.nameKo || current.nameJa} /></div><div className="min-w-0 flex-1 space-y-3"><p className="break-words text-sm text-stone-600">선택한 제품: <Link className="underline" href={`/sake/${current.no}`}>{current.nameKo || current.nameJa}</Link></p>
                            {selectedDetail.data && <ProductFacts product={selectedDetail.data} />}</div></div>}
                    </div>}
                <button className="ui-button" disabled={!allowed || busy || (mode === "SAKE" ? !food.trim() : !current)}>{busy ? "추천 요청 확인 중…" : "추천받기"}</button>
                <p className="text-xs leading-6 text-stone-500">먼저 최근 기록을 확인합니다. 같은 조건의 유효한 기록이 있으면 무료 열람과 새 요청 중 선택할 수 있습니다.</p>
            </form>
        </section>
        {busy && <p role="status" className="ui-state">추천 요청을 확인하고 있습니다. 완료될 때까지 잠시 기다려 주세요.</p>}
        {error && <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-5 text-sm leading-7 text-red-800"><p>{error}</p><p className="mt-2">이전 기록 확인에 실패하면 새 과금을 진행하지 않습니다. 응답을 받지 못한 추천 요청은 같은 조건으로 다시 확인해 주세요.</p></div>}
        {currentDuplicate && <section ref={notice} tabIndex={-1} role="region" aria-labelledby="duplicate-title" className="rounded-lg border border-amber-300 bg-amber-50 p-6 focus-visible:outline-2 focus-visible:outline-stone-700">
            <h2 id="duplicate-title" className="text-lg font-semibold">이전에 동일한 조건으로 추천받은 기록이 있습니다.</h2>
            <p className="mt-3 text-sm leading-7 text-stone-600">추천일 {new Date(currentDuplicate.previous.completedAtEpoch * 1000).toLocaleString("ko-KR")} · 열람 만료 {new Date(currentDuplicate.previous.expiresAtEpoch * 1000).toLocaleString("ko-KR")}</p>
            <p className="mt-2 text-sm leading-7">새 요청도 공용 캐시를 사용할 수 있어 결과가 이전과 같을 수 있습니다.</p>
            <div className="mt-5 flex flex-wrap gap-3"><Link className="ui-button" href={`/account/recommendations/${currentDuplicate.previous.id}`}>이전 추천 결과 보기 - 무료</Link>
                <button type="button" className="ui-button-secondary" disabled={busy || !account.data?.balance} onClick={() => execute(currentDuplicate.condition, true)}>새롭게 추천받기 - 1크레딧</button>
                <button type="button" className="min-h-11 px-3 text-sm underline" onClick={() => setDuplicate(null)}>취소</button></div>
            {!account.data?.balance && <p className="mt-3 text-sm">현재 크레딧이 부족합니다. 이전 결과는 무료로 확인할 수 있습니다.</p>}
        </section>}
        {result && <PairingResult result={snapshotView(result)} />}
    </main></>;
}
