import Link from "next/link";
import type {SakeListItem} from "@/types/sake";
import {getSakeTypeLabel,getPrefectureLabel} from "@/commons/sake";

interface SakeCardProps{
    sake:SakeListItem;
}

export default function SakeCard({sake}:SakeCardProps){
    return (
        <Link href={`/sake/${sake.no}`} className={"group block h-full rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
            <article className={"flex h-full min-w-0 flex-col overflow-hidden rounded-lg border border-stone-200 bg-white transition-colors group-hover:border-stone-400"}>
                <div className={"aspect-4/5 bg-stone-100 p-4"}>
                    <img src={sake.imageUrl || "/images/sake-placeholder.png"}
                         alt={sake.nameKo || sake.nameJa}
                         className={"h-full w-full object-contain"}/>
                </div>

                <div className={"flex min-w-0 flex-1 flex-col p-4"}>
                    {/* 사케명 */}
                    <h2 className={"line-clamp-2 break-words text-[15px] font-semibold leading-6 text-stone-900 [overflow-wrap:anywhere]"}>
                        {sake.nameKo || sake.nameJa}
                    </h2>

                    {sake.nameKo && (
                        <p className={"mt-1 line-clamp-1 break-words text-xs text-stone-500 [overflow-wrap:anywhere]"}>
                            {sake.nameJa}
                        </p>
                    )}

                    <div className={"mt-auto flex flex-wrap gap-x-3 gap-y-1 pt-4 text-xs text-stone-600"}>
                        {/* 종류 */}
                        {sake.sakeType && (
                            <div>
                                <p>
                                    {getSakeTypeLabel(sake.sakeType)}
                                </p>
                            </div>
                        )}

                        {/* 지역 */}
                        {sake.prefecture && (
                            <div>
                                <p>
                                    {getPrefectureLabel(sake.prefecture)}
                                </p>
                            </div>
                        )}
                    </div>
                </div>
            </article>
        </Link>
    );
}
