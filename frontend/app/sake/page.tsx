"use client";

import {useQuery} from "@tanstack/react-query";
import {getSakeList} from "@/app/api/sake";
import SakeCard from "@/components/sake/SakeCard";
import React, {SubmitEvent,useState} from "react";
import Header from "@/components/layout/Header";
import {SAKE_TYPES} from "@/commons/sake";

export default function SakePage(){
    // 검색창에 입력되어있는 값
    const [keyword,setKeyword]=useState("");
    // 실제 API 검색 입력값
    const [searchKeyword,setSearchKeyword]=useState("");
    // 현재 페이지
    const [page,setPage]=useState(1);
    // 선택 종류
    const [sakeType,setSakeType]=useState("");
    // 사케 목록 조회
    const {data,isLoading,isError,isFetching}=useQuery({
        queryKey:["sakeList",page,searchKeyword,sakeType],
        queryFn:()=>getSakeList(page,searchKeyword,sakeType),
        placeholderData:(previousData)=>previousData,
        retry:false
    });
    // 검색버튼 이벤트 처리
    const handleSearch=(e:SubmitEvent<HTMLFormElement>)=>{
        e.preventDefault();
        setPage(1);
        setSearchKeyword(keyword.trim());
    };
    // 종류 변경시 첫 페이지부터 조회
    const handleTypeChange=(type:string)=>{
        setPage(1);
        setSakeType(type);
    }



    return (
        <>
            <Header/>

            <main className={"pb-16"}>
                {/* 목록 상단 검색 영역 */}
                <section className={"border-b border-stone-200 bg-white"}>
                    <div className={"page-shell py-8 sm:py-10"}>
                        <h1 className={"text-2xl font-semibold tracking-tight text-stone-900"}>
                            사케 찾기
                        </h1>

                        <p className={"mt-1.5 text-sm text-stone-500"}>
                            이름을 검색하거나 종류로 살펴보세요.
                        </p>

                        <form onSubmit={handleSearch} className={"mt-6 flex max-w-2xl gap-2"}>
                            <input type="text" value={keyword} aria-label={"사케 이름 검색"}
                                   onChange={(e)=>setKeyword(e.target.value)}
                                   placeholder="사케 이름을 입력하세요"
                                   className={"ui-input flex-1"}/>

                            <button type="submit"
                                    className={"ui-button"}>
                                검색
                            </button>
                        </form>

                        {/* 사케 종류 필터 */}
                        <div className={"mt-5 flex flex-wrap gap-1.5"} aria-label={"사케 종류 필터"}>
                            {SAKE_TYPES.filter((type)=>type.value!=="非公開").map((type)=>(
                                <button key={type.value} type="button" onClick={()=>handleTypeChange(type.value)} aria-pressed={sakeType===type.value}
                                        className={`rounded-md border px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${
                                            sakeType===type.value
                                                ? "border-stone-800 bg-stone-800 text-white"
                                                : "border-stone-200 bg-white text-stone-600 hover:border-stone-500 hover:text-stone-900"
                                        }`}>
                                    {type.label}
                                </button>
                            ))}
                        </div>
                    </div>
                </section>

                {/* 사케 목록 */}
                <section className={"page-shell py-8 sm:py-10"}>
                    <div className={"mb-5 flex items-end justify-between gap-3"}>
                        <h2 className={"text-lg font-semibold text-stone-900"}>
                            사케 목록
                        </h2>

                        {data && (
                            <p className={"shrink-0 text-sm tabular-nums text-stone-500"}>
                                총 {data.count.toLocaleString()}개
                            </p>
                        )}
                    </div>

                    {isLoading && (
                        <div className={"grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-5 lg:grid-cols-4"} aria-label={"사케 목록 불러오는 중"}>
                            {Array.from({length:8},(_,index)=>(
                                <div key={index} className={"ui-panel animate-pulse overflow-hidden"}>
                                    <div className={"aspect-4/5 bg-stone-100"}/>
                                    <div className={"space-y-2 p-4"}><div className={"h-4 w-3/4 rounded bg-stone-100"}/><div className={"h-3 w-1/2 rounded bg-stone-100"}/></div>
                                </div>
                            ))}
                        </div>
                    )}

                    {isError && (
                        <div className={"ui-state"}>
                            목록을 불러오지 못했습니다.
                        </div>
                    )}

                    {data && data.list.length===0 && (
                        <div className={"ui-state"}>
                            검색 결과가 없습니다.
                        </div>
                    )}

                    {data && data.list.length>0 && (
                        <>
                            {/* 조회된 사케 출력 */}
                            <div className={`grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-5 lg:grid-cols-4 ${isFetching ? "opacity-60" : ""}`} aria-busy={isFetching}>
                                {data.list.map((sake)=>(
                                    <SakeCard key={sake.no} sake={sake}/>
                                ))}
                            </div>

                            {/* 페이지네이션 */}
                            <div className={"mt-9 flex flex-wrap items-center justify-center gap-1"}>
                                <button type="button" onClick={()=>setPage(data.startPage-1)} disabled={data.startPage===1}
                                    className={"rounded-md px-3 py-2 text-sm text-stone-600 transition hover:bg-stone-100 focus-visible:outline-2 focus-visible:outline-stone-700 disabled:cursor-not-allowed disabled:text-stone-300"}>
                                    이전
                                </button>

                                {Array.from(
                                    {length:data.endPage-data.startPage+1},
                                    (_,index)=>data.startPage+index
                                ).map((pageNumber)=>(
                                    <button key={pageNumber} type="button" onClick={()=>setPage(pageNumber)}
                                        className={`h-9 min-w-9 rounded-md px-2 text-sm tabular-nums transition focus-visible:outline-2 focus-visible:outline-stone-700 ${
                                            page===pageNumber
                                                ? "bg-stone-800 font-medium text-white"
                                                : "text-stone-600 hover:bg-stone-100"
                                        }`}>
                                        {pageNumber}
                                    </button>
                                ))}

                                <button type="button" onClick={()=>setPage(data.endPage+1)} disabled={data.endPage===data.totalpage}
                                    className={"rounded-md px-3 py-2 text-sm text-stone-600 transition hover:bg-stone-100 focus-visible:outline-2 focus-visible:outline-stone-700 disabled:cursor-not-allowed disabled:text-stone-300"}>
                                    다음
                                </button>
                            </div>
                        </>
                    )}
                </section>
            </main>
        </>
    )
}
