import type {SakeListItem} from "@/app/types/sake";

// 부모 컴포넌트에서 전달 받는 데이터 타입
interface SakeCardProps {
    sake:SakeListItem;
}

// 사케 목록 카드
export default function SakeCard({sake}:SakeCardProps){
    return (
        <article className={"overflow-hidden rounded-xl border border-gray-200 bg-white"}>
            <div className={"aspect-[3/4] bg-gray-100"}>
                <img src={sake.imageUrl || "/images/sake-placeholder.png"}
                     alt={sake.nameKo || sake.nameJa}
                     className={"h-full w-full object-contain"}/>
            </div>

            <div className={"p-4"}>
                <h2 className={"font-semibold"}>
                    {sake.nameKo || sake.nameJa}
                </h2>

                {sake.nameKo && (
                    <p className={"mt-1 text-sm text-gray-500"}>
                        {sake.nameJa}
                    </p>
                )}

                <div className={"mt-3 text-sm text-gray-600"}>
                    {sake.sakeType && <p>{sake.sakeType}</p>}
                    {sake.breweryNameKo && <p>{sake.breweryNameKo}</p>}
                    {sake.prefecture && <p>{sake.prefecture}</p>}
                </div>
            </div>
        </article>
    )
}