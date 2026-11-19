"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { getExamples } from "@/app/api/examples";
import PairingResult from "./PairingResult";

export default function ExampleBrowser({ compact = false }: { compact?: boolean }) {
    const query = useQuery({ queryKey: ["public-pairing-examples"], queryFn: getExamples, retry: false, staleTime: 300000 });
    const [slug, setSlug] = useState<string | null>(null);
    if (query.isPending) return <p role="status" className="ui-state">공개 예시를 불러오는 중입니다…</p>;
    if (query.isError) return <div role="status" className="ui-state"><p>지금은 예시를 불러올 수 없습니다. 사케 탐색은 계속 이용할 수 있습니다.</p>
        <button className="ui-button-secondary mt-4" onClick={() => query.refetch()}>예시 다시 불러오기</button></div>;
    if (!query.data?.length) return <p className="ui-state">공개 예시를 준비하고 있습니다. 먼저 사케 목록을 둘러보세요.</p>;
    const examples = compact ? query.data.slice(0, 2) : query.data;
    const selected = examples.find(item => item.slug === slug) ?? examples[0];
    return <div className="space-y-6">
        <div className="flex flex-wrap gap-2" aria-label="공개 예시 선택">{examples.map(item => <button key={item.slug} type="button" aria-pressed={selected.slug === item.slug}
            onClick={() => setSlug(item.slug)} className={`min-h-11 rounded-full border px-4 py-2 text-sm transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${selected.slug === item.slug ? "border-stone-800 bg-stone-800 text-white" : "border-stone-300 bg-white text-stone-700 hover:bg-stone-100"}`}>
            {item.title}</button>)}</div>
        <p className="rounded-lg bg-amber-50 p-4 text-sm leading-6 text-stone-700"><strong>직접 작성한 편집 예시</strong> · {selected.sourceNote} 신규 AI 호출이나 크레딧 사용은 발생하지 않습니다.</p>
        <PairingResult result={selected} />
        {compact && <Link href="/examples" className="ui-button-secondary">양방향 예시 모두 보기</Link>}
    </div>;
}
