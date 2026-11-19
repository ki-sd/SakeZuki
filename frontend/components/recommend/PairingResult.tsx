import Link from "next/link";
import SakeImage from "@/components/sake/SakeImage";
import { getSakeTypeLabel } from "@/commons/sake";
import type { PairingView } from "@/types/pairing";

export default function PairingResult({ result }: { result: PairingView }) {
    // 공개 편집 예시와 개인 스냅샷은 표시만 공유한다. 이 컴포넌트에는 조회·과금 동작을 넣지 않는다.
    return <section aria-label="추천 결과" className="space-y-5">
        <div><p className="text-xs font-medium tracking-wider text-stone-500">{result.kind === "SAKE" ? "음식 → 사케" : "사케 → 음식"}</p>
            <h2 className="mt-2 text-2xl font-semibold tracking-tight">{result.inputLabel}</h2></div>
        {result.selectedSake && <Link className="inline-block text-sm underline underline-offset-4" href={`/sake/${result.selectedSake.no}`}>선택한 사케 상세정보</Link>}
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {result.sakes.map(sake => <article key={sake.no} className="ui-panel overflow-hidden">
                <div className="relative h-44 bg-stone-50 p-4"><SakeImage src={sake.imageUrl ?? null} alt={sake.nameKo || sake.nameJa} /></div>
                <div className="space-y-3 p-5"><p className="text-xs text-stone-500">{getSakeTypeLabel(sake.sakeType)}</p>
                    <h3 className="font-semibold leading-relaxed"><Link href={`/sake/${sake.no}`} className="underline-offset-4 hover:underline">{sake.nameKo || sake.nameJa}</Link></h3>
                    {sake.nameKo && <p lang="ja" className="text-xs text-stone-500">{sake.nameJa}</p>}
                    <p className="text-sm leading-7 text-stone-600">{sake.reason}</p>
                    <Link className="inline-block text-sm font-medium underline underline-offset-4" href={`/sake/${sake.no}`}>사케 자세히 보기</Link>
                </div>
            </article>)}
            {result.foods.map((food, index) => <article key={`${food.name}-${index}`} className="ui-panel p-6">
                <span aria-hidden="true" className="text-sm text-stone-400">0{index + 1}</span><h3 className="mt-5 text-lg font-semibold">{food.name}</h3>
                <p className="mt-3 text-sm leading-7 text-stone-600">{food.reason}</p>
            </article>)}
        </div>
        {result.sakes.length === 0 && result.foods.length === 0 && <p className="ui-state">표시할 추천 결과가 없습니다.</p>}
    </section>;
}
