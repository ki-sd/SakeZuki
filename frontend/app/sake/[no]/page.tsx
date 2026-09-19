"use client";

import {useState} from "react";
import {useParams} from "next/navigation";
import {useQuery} from "@tanstack/react-query";
import {getSakeDetail} from "@/app/api/sake";
import Header from "@/components/layout/Header";
import Link from "next/link";
import {getSakeTypeLabel,getPrefectureLabel} from "@/commons/sake";

type DetailTab="sake" | "brand" | "brewery";

export default function SakeDetailPage(){
    // [no]값 갖고옴
    const params=useParams<{no:string}>();
    const no=Number(params.no);

    // 현재 선택된 탭
    const [activeTab,setActiveTab]=useState<DetailTab>("sake");

    // 상세 조회
    const {data,isLoading,isError}=useQuery({
        queryKey:["sakeDetail",no],
        queryFn:()=>getSakeDetail(no),
        enabled:Number.isInteger(no) && no>0,
        retry:false
    });

    if(isLoading){
        return (
            <>
                <Header/>
                <main className={"mx-auto max-w-7xl px-6 py-20 text-center text-gray-500"}>
                    불러오는 중...
                </main>
            </>
        )
    }
    if(isError || !data) {
        return (
            <>
                <Header/>
                <main className={"mx-auto max-w-7xl px-6 py-20 text-center text-gray-500"}>
                    정보를 불러오지 못했습니다.
                </main>
            </>
        );
    }
    return (
        <>
            <Header/>

            <main className={"mx-auto max-w-7xl px-6 py-10"}>
                <Link href="/sake"
                    className={"mb-6 inline-flex items-center gap-2 text-sm font-medium text-gray-500 transition hover:text-gray-900"}>
                    <span>←</span>
                    <span>목록으로 돌아가기</span>
                </Link>
                {/* 사케 기본정보 */}
                <section className={"grid gap-10 md:grid-cols-[360px_1fr]"}>
                    <div className={"overflow-hidden rounded-xl border border-gray-200 bg-white"}>
                        <div className={"aspect-[3/4] bg-gray-50"}>
                            <img src={data.imageUrl || "/images/sake-placeholder.png"} alt={data.nameKo || data.nameJa}
                                className={"h-full w-full object-contain"}/>
                        </div>
                    </div>

                    <div className={"flex flex-col justify-center"}>
                        <p className={"text-sm font-medium text-gray-900"}>
                            {getSakeTypeLabel(data.sakeType)}
                        </p>

                        {data.sakeType && (
                            <p className={"mt-1 text-xs text-gray-400"}>
                                {data.sakeType}
                            </p>
                        )}

                        <h1 className={"mt-2 text-3xl font-bold text-gray-900"}>
                            {data.nameKo || data.nameJa}
                        </h1>

                        {data.nameKo && (
                            <p className={"mt-2 text-lg text-gray-500"}>
                                {data.nameJa}
                            </p>
                        )}

                        {data.nameKana && (
                            <p className={"mt-1 text-sm text-gray-400"}>
                                {data.nameKana}
                            </p>
                        )}

                        <div className={"mt-6 flex flex-wrap gap-2"}>
                            {data.brewery?.prefecture && (
                                <span className={"rounded-full bg-gray-100 px-3 py-1 text-sm text-gray-600"}>
                                    {getPrefectureLabel(data.brewery.prefecture)}
                                </span>
                            )}

                            {data.brand?.nameJa && (
                                <span className={"rounded-full bg-gray-100 px-3 py-1 text-sm text-gray-600"}>
                                    {data.brand.nameKo || data.brand.nameJa}
                                </span>
                            )}

                            {data.brewery?.nameJa && (
                                <span className={"rounded-full bg-gray-100 px-3 py-1 text-sm text-gray-600"}>
                                    {data.brewery.nameKo || data.brewery.nameJa}
                                </span>
                            )}
                        </div>
                    </div>
                </section>

                {/* 상세정보 탭 */}
                <section className={"mt-12"}>
                    <div className={"flex border-b border-gray-200"}>
                        <button type="button" onClick={()=>setActiveTab("sake")}
                            className={`px-6 py-3 text-sm font-medium transition ${
                                activeTab==="sake"
                                    ? "border-b-2 border-gray-900 text-gray-900"
                                    : "text-gray-500 hover:text-gray-900"
                                }`
                            }>
                            사케 정보
                        </button>

                        <button type="button" onClick={()=>setActiveTab("brand")}
                            className={`px-6 py-3 text-sm font-medium transition ${
                                activeTab==="brand"
                                    ? "border-b-2 border-gray-900 text-gray-900"
                                    : "text-gray-500 hover:text-gray-900"
                                }`
                            }>
                            브랜드
                        </button>

                        <button type="button" onClick={()=>setActiveTab("brewery")}
                            className={`px-6 py-3 text-sm font-medium transition ${
                                activeTab==="brewery"
                                    ? "border-b-2 border-gray-900 text-gray-900"
                                    : "text-gray-500 hover:text-gray-900"
                                }`
                            }>
                            양조장
                        </button>
                    </div>

                    <div className={"py-8"}>
                        {/* 사케 정보 */}
                        {activeTab==="sake" && (
                            <div className={"grid gap-x-12 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
                                <InfoItem label="종류"
                                    value={getSakeTypeLabel(data.sakeType)}
                                    subValue={data.sakeType}/>
                                <InfoItem label="사용 쌀" value={data.rice}/>
                                <InfoItem label="정미보합" value={data.polishingRatio}/>
                                <InfoItem label="효모" value={data.yeast}/>
                                <InfoItem label="일본주도" value={data.sakeMeterValue}/>
                                <InfoItem label="산도" value={data.acidity}/>
                                <InfoItem label="알코올 도수"
                                    value={data.alcoholPercentage ? `${data.alcoholPercentage}%` : null}/>
                            </div>
                        )}

                        {/* 브랜드 정보 */}
                        {activeTab==="brand" && (
                            <>
                                {data.brand ? (
                                    <div className={"grid gap-x-12 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
                                        <InfoItem label="브랜드명"
                                            value={data.brand.nameKo || data.brand.nameJa}
                                            subValue={data.brand.nameKo ? data.brand.nameJa : null}/>
                                        <InfoItem label="읽는 법"
                                            value={data.brand.nameKana}/>
                                    </div>
                                ) : (
                                    <EmptyInfo/>
                                )}
                            </>
                        )}

                        {/* 양조장 정보 */}
                        {activeTab==="brewery" && (
                            <div className={"grid gap-x-12 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
                                <InfoItem label="양조장" value={data.brewery.nameKo || data.brewery.nameJa}
                                    subValue={data.brewery.nameKo ? data.brewery.nameJa : null}/>

                                <InfoItem label="읽는 법"
                                    value={data.brewery.nameKana}/>

                                <InfoItem label="법인명"
                                    value={data.brewery.corporationName}/>

                                <InfoItem label="창업"
                                    value={data.brewery.foundedYear}/>

                                <InfoItem label="대표자"
                                    value={data.brewery.ceo}/>

                                <InfoItem label="지역"
                                    value={getPrefectureLabel(data.brewery.prefecture)}
                                    subValue={data.brewery.prefecture}/>

                                <InfoItem label="주소"
                                    value={data.brewery.address}/>

                                <InfoItem label="우편번호"
                                    value={data.brewery.post}/>

                                <InfoItem label="전화"
                                    value={data.brewery.phone}/>

                                <InfoItem label="팩스"
                                    value={data.brewery.fax}/>

                                <InfoItem label="이메일"
                                    value={data.brewery.email}/>

                                <InfoItem label="견학"
                                    value={data.brewery.tourAvailable===true
                                        ? "가능"
                                        : data.brewery.tourAvailable===false
                                            ? "불가"
                                            : null
                                    }/>

                                {data.brewery.website && (
                                    <div>
                                        <p className={"text-sm text-gray-500"}>
                                            홈페이지
                                        </p>

                                        <a href={data.brewery.website} target="_blank" rel="noopener noreferrer"
                                           className={"mt-1 inline-block font-medium text-gray-900 underline underline-offset-4"}>
                                            홈페이지 방문
                                        </a>
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                </section>
            </main>
        </>
    );
}
interface InfoItemProps{
    label:string;
    value:string | null | undefined;
    subValue?:string | null;
}

// 공통 사용
function InfoItem({label,value,subValue}:InfoItemProps){
    return (
        <div>
            <p className={"text-sm text-gray-500"}>{label}</p>
            <p className={"mt-1 font-medium text-gray-900"}>{value || "-"}</p>
            {subValue && value!==subValue && (
                <p className={"mt-1 text-sm text-gray-400"}>{subValue}</p>
            )}
        </div>
    );
}
// 정보 X
function EmptyInfo(){
    return (
        <div className={"py-10 text-center text-gray-500"}>
            등록된 정보가 없습니다.
        </div>
    );
}
