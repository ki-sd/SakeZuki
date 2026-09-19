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

            <main>
                {/* 목록 상단 검색 영역 */}
                <section className={"border-b border-gray-200 bg-gray-50"}>
                    <div className={"mx-auto max-w-7xl px-6 py-12 text-center"}>
                        <h1 className={"text-3xl font-bold text-gray-900"}>
                            마음에 드는 사케를 찾아보세요
                        </h1>

                        <p className={"mt-3 text-gray-600"}>
                            이름으로 원하는 사케를 검색할 수 있습니다.
                        </p>

                        <form onSubmit={handleSearch} className={"mx-auto mt-6 flex max-w-xl"}>
                            <input type="text" value={keyword}
                                   onChange={(e)=>setKeyword(e.target.value)}
                                   placeholder="사케 이름을 입력하세요"
                                   className={"min-w-0 flex-1 rounded-l-lg border border-gray-300 bg-white px-4 py-3 text-gray-900 outline-none focus:border-gray-500"}/>

                            <button type="submit"
                                    className={"rounded-r-lg bg-gray-900 px-6 py-3 font-medium text-white transition hover:bg-gray-700"}>
                                검색
                            </button>
                        </form>

                        {/* 사케 종류 필터 */}
                        <div className={"mt-6 flex flex-wrap justify-center gap-2"}>
                            {SAKE_TYPES.filter((type)=>type.value!=="非公開").map((type)=>(
                                <button key={type.value} type="button" onClick={()=>handleTypeChange(type.value)}
                                        className={`rounded-full border px-4 py-2 text-sm transition ${
                                            sakeType===type.value
                                                ? "border-gray-900 bg-gray-900 text-white"
                                                : "border-gray-300 bg-white text-gray-600 hover:border-gray-500 hover:text-gray-900"
                                        }`}>
                                    {type.label}
                                </button>
                            ))}
                        </div>
                    </div>
                </section>

                {/* 사케 목록 */}
                <section className={"mx-auto w-full max-w-7xl px-6 py-8"}>
                    <div className={"mb-6 flex items-end justify-between"}>
                        <h2 className={"text-2xl font-bold text-gray-900"}>
                            사케 목록
                        </h2>

                        {data && (
                            <p className={"text-sm text-gray-500"}>
                                총 {data.count.toLocaleString()}개
                            </p>
                        )}
                    </div>

                    {isLoading && (
                        <div className={"py-20 text-center text-gray-500"}>
                            로딩 중...
                        </div>
                    )}

                    {isError && (
                        <div className={"py-20 text-center text-gray-500"}>
                            목록을 불러오지 못했습니다.
                        </div>
                    )}

                    {data && data.list.length===0 && (
                        <div className={"py-20 text-center text-gray-500"}>
                            검색 결과가 없습니다.
                        </div>
                    )}

                    {data && data.list.length>0 && (
                        <>
                            {/* 조회된 사케 출력 */}
                            <div className={"grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"}>
                                {data.list.map((sake)=>(
                                    <SakeCard key={sake.no} sake={sake}/>
                                ))}
                            </div>

                            {/* 페이지네이션 */}
                            <div className={"mt-10 flex items-center justify-center gap-1"}>
                                <button type="button" onClick={()=>setPage(data.startPage-1)} disabled={data.startPage===1}
                                    className={"rounded px-3 py-2 text-sm text-gray-600 transition hover:bg-gray-100 disabled:cursor-not-allowed disabled:text-gray-300"}>
                                    이전
                                </button>

                                {Array.from(
                                    {length:data.endPage-data.startPage+1},
                                    (_,index)=>data.startPage+index
                                ).map((pageNumber)=>(
                                    <button key={pageNumber} type="button" onClick={()=>setPage(pageNumber)}
                                        className={`h-9 min-w-9 rounded px-2 text-sm transition ${
                                            page===pageNumber
                                                ? "bg-gray-900 font-medium text-white"
                                                : "text-gray-600 hover:bg-gray-100"
                                        }`}>
                                        {pageNumber}
                                    </button>
                                ))}

                                <button type="button" onClick={()=>setPage(data.endPage+1)} disabled={data.endPage===data.totalpage}
                                    className={"rounded px-3 py-2 text-sm text-gray-600 transition hover:bg-gray-100 disabled:cursor-not-allowed disabled:text-gray-300"}>
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