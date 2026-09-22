import Link from "next/link";
import type {SakeListItem} from "@/types/sake";
import {getSakeTypeLabel,getPrefectureLabel} from "@/commons/sake";
import Image from "next/image";
import SakeImage from "@/components/sake/SakeImage";

// 목록 페이지가 받은 API 한 건을 props로 전달한다. 카드는 조회 상태를 갖지 않고 표시만 담당한다.
interface SakeCardProps{
    sake:SakeListItem;
}

export default function SakeCard({sake}:SakeCardProps){
    return (
        <Link href={`/sake/${sake.no}`} className={"group block h-full rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
            <article className={"flex h-full min-w-0 flex-col overflow-hidden rounded-lg border border-stone-200 bg-white transition-colors group-hover:border-stone-400"}>
                <div className={"relative aspect-4/5 bg-stone-100 p-4"}>
                    {/* 이미지가 없는 제품의 대체 표시는 SakeImage에 모아 카드마다 같은 규칙을 쓴다. */}
                    <SakeImage src={sake.imageUrl}
                               alt={sake.nameKo || sake.nameJa}/>
                </div>

                <div className={"flex min-w-0 flex-1 flex-col p-4"}>
                    {/* 한국어 번역이 없는 제품은 일본어 원문을 표시해 목록에서 빠지지 않게 한다. */}
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
