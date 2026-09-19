"use client";

import {useQuery} from "@tanstack/react-query";
import {getSakeList} from "@/app/api/sake";
import SakeCard from "@/components/sake/SakeCard";

export default function SakePage(){
    // 사케 목록 조회
    const {data,isLoading,isError}=useQuery({
        queryKey:["sakeList",1],
        queryFn:()=>getSakeList(1)
    });
    if(isLoading) return <div>로딩 중...</div>;
    if(isError) return <div>목록을 불러오지 못했습니다.</div>;

    return (
        <main className={"mx-auto w-full max-w-7xl px-6 py-8"}>
            <h1 className={"mb-6 text-2xl font-bold"}>사케 목록</h1>

            <div className={"grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"}>
                {data?.list.map((sake)=>(
                    <SakeCard key={sake.no} sake={sake}/>
                ))}
            </div>
        </main>
    )
}