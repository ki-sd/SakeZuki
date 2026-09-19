import Link from "next/link";
import type {SakeListItem} from "@/types/sake";
import {getSakeTypeLabel,getPrefectureLabel} from "@/commons/sake";

interface SakeCardProps{
    sake:SakeListItem;
}

export default function SakeCard({sake}:SakeCardProps){
    return (
        <Link href={`/sake/${sake.no}`} className={"block"}>
            <article className={"overflow-hidden rounded-xl border border-gray-200 bg-white transition hover:-translate-y-1 hover:shadow-md"}>
                <div className={"aspect-3/4 bg-gray-50"}>
                    <img src={sake.imageUrl || "/images/sake-placeholder.png"}
                         alt={sake.nameKo || sake.nameJa}
                         className={"h-full w-full object-contain"}/>
                </div>

                <div className={"p-4"}>
                    {/* 사케명 */}
                    <h2 className={"font-semibold text-gray-900"}>
                        {sake.nameKo || sake.nameJa}
                    </h2>

                    {sake.nameKo && (
                        <p className={"mt-1 text-sm text-gray-400"}>
                            {sake.nameJa}
                        </p>
                    )}

                    <div className={"mt-3"}>
                        {/* 종류 */}
                        {sake.sakeType && (
                            <div>
                                <p className={"text-sm text-gray-600"}>
                                    {getSakeTypeLabel(sake.sakeType)}
                                </p>
                                <p className={"text-xs text-gray-400"}>
                                    {sake.sakeType}
                                </p>
                            </div>
                        )}

                        {/* 지역 */}
                        {sake.prefecture && (
                            <div className={"mt-2"}>
                                <p className={"text-sm text-gray-600"}>
                                    {getPrefectureLabel(sake.prefecture)}
                                </p>
                                <p className={"text-xs text-gray-400"}>
                                    {sake.prefecture}
                                </p>
                            </div>
                        )}
                    </div>
                </div>
            </article>
        </Link>
    );
}