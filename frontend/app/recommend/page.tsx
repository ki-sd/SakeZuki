"use client";

import {useState} from "react";
import Link from "next/link";
import {useSearchParams} from "next/navigation";
import {useMutation,useQuery} from "@tanstack/react-query";
import {recommendFood,recommendSake} from "@/app/api/recommend";
import {getSakeDetail,searchSake} from "@/app/api/sake";
import type {SakeSearchItem} from "@/types/sake";
import Header from "@/components/layout/Header";

type RecommendMode="sake" | "food";

export default function RecommendPage(){
    const searchParams=useSearchParams();
    const sakeNoParam=searchParams.get("sakeNo");
    const initialSakeNo=sakeNoParam ? Number(sakeNoParam) : null;
    const hasInitialSake=initialSakeNo!==null && Number.isInteger(initialSakeNo) && initialSakeNo>0;

    const [mode,setMode]=useState<RecommendMode>(hasInitialSake ? "food" : "sake");
    const [food,setFood]=useState("");
    const [sakeSearch,setSakeSearch]=useState("");
    const [selectedSake,setSelectedSake]=useState<SakeSearchItem | null>(null);

    const initialSakeQuery=useQuery({
        queryKey:["sakeDetail",initialSakeNo],
        queryFn:()=>getSakeDetail(initialSakeNo!),
        enabled:hasInitialSake,
        retry:false
    });

    const initialSake:SakeSearchItem | null=initialSakeQuery.data ? {
        no:initialSakeQuery.data.no,
        nameKo:initialSakeQuery.data.nameKo,
        nameJa:initialSakeQuery.data.nameJa,
        imageUrl:initialSakeQuery.data.imageUrl
    } : null;

    const currentSake=selectedSake || initialSake;

    const sakeMutation=useMutation({
        mutationFn:recommendSake
    });

    const foodMutation=useMutation({
        mutationFn:recommendFood
    });

    const searchQuery=useQuery({
        queryKey:["sakeSearch",sakeSearch],
        queryFn:()=>searchSake(sakeSearch.trim()),
        enabled:mode==="food" && sakeSearch.trim().length>=2 && !selectedSake && !hasInitialSake,
        staleTime:30000
    });

    const handleSakeRecommend=()=>{
        const value=food.trim();

        if(!value || sakeMutation.isPending){
            return;
        }

        sakeMutation.mutate({
            food:value
        });
    };

    const handleFoodRecommend=()=>{
        if(!currentSake || foodMutation.isPending){
            return;
        }

        foodMutation.mutate({
            sakeNo:currentSake.no
        });
    };

    const handleSakeSelect=(sake:SakeSearchItem)=>{
        setSelectedSake(sake);
        setSakeSearch(sake.nameKo || sake.nameJa);
        foodMutation.reset();
    };

    const handleSakeSearchChange=(value:string)=>{
        setSakeSearch(value);
        setSelectedSake(null);
        foodMutation.reset();
    };

    const handleModeChange=(nextMode:RecommendMode)=>{
        setMode(nextMode);
    };

    return (
        <>
            <Header/>

            <main className={"mx-auto w-full max-w-7xl px-6 py-10"}>
                <section className={"mb-8"}>
                    <p className={"mb-2 text-sm font-medium text-gray-500"}>
                        AI 추천
                    </p>

                    <h1 className={"text-3xl font-bold tracking-tight text-gray-900"}>
                        오늘 뭐랑 마실까요?
                    </h1>

                    <p className={"mt-3 text-gray-500"}>
                        음식에 어울리는 사케를 찾거나, 사케에 어울리는 음식을 추천받아보세요.
                    </p>
                </section>

                <div className={"mb-8 flex border-b border-gray-200"}>
                    <button type={"button"} onClick={()=>handleModeChange("sake")}
                            className={`border-b-2 px-5 py-3 text-sm font-semibold transition ${
                                mode==="sake"
                                    ? "border-gray-900 text-gray-900"
                                    : "border-transparent text-gray-400 hover:text-gray-700"
                            }`}>
                        음식으로 사케 찾기
                    </button>

                    <button type={"button"} onClick={()=>handleModeChange("food")}
                            className={`border-b-2 px-5 py-3 text-sm font-semibold transition ${
                                mode==="food"
                                    ? "border-gray-900 text-gray-900"
                                    : "border-transparent text-gray-400 hover:text-gray-700"
                            }`}>
                        사케로 음식 찾기
                    </button>
                </div>

                {mode==="sake" && (
                    <>
                        <section className={"rounded-2xl border border-gray-200 bg-white p-6"}>
                            <label htmlFor={"food"} className={"mb-2 block text-sm font-semibold text-gray-800"}>
                                음식
                            </label>

                            <div className={"flex gap-3"}>
                                <input id={"food"} type={"text"} value={food}
                                       onChange={(e)=>setFood(e.target.value)}
                                       onKeyDown={(e)=>{
                                           if(e.key==="Enter"){
                                               handleSakeRecommend();
                                           }
                                       }}
                                       placeholder={"예: 삼겹살, 스키야키, 회"} disabled={sakeMutation.isPending}
                                       className={"min-w-0 flex-1 rounded-lg border border-gray-300 px-4 py-3 outline-none transition focus:border-gray-500 disabled:bg-gray-50"}
                                />

                                <button type={"button"} onClick={handleSakeRecommend}
                                        disabled={!food.trim() || sakeMutation.isPending}
                                        className={"shrink-0 rounded-lg bg-gray-900 px-6 py-3 font-semibold text-white transition hover:bg-gray-700 disabled:cursor-not-allowed disabled:bg-gray-300"}>
                                    {sakeMutation.isPending ? "추천 중..." : "추천받기"}
                                </button>
                            </div>
                        </section>

                        {sakeMutation.isPending && (
                            <section className={"mt-8 rounded-2xl border border-gray-200 bg-gray-50 px-6 py-12 text-center"}>
                                <div className={"mx-auto mb-4 h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-gray-800"}/>

                                <p className={"font-semibold text-gray-800"}>
                                    음식과 잘 어울리는 사케를 찾고 있어요.
                                </p>

                                <p className={"mt-2 text-sm text-gray-500"}>
                                    처음 추천하는 음식은 시간이 조금 걸릴 수 있어요.
                                </p>
                            </section>
                        )}

                        {sakeMutation.isError && (
                            <section className={"mt-8 rounded-2xl border border-red-200 bg-red-50 p-6"}>
                                <p className={"font-semibold text-red-700"}>
                                    추천을 불러오지 못했습니다.
                                </p>

                                <p className={"mt-1 text-sm text-red-600"}>
                                    잠시 후 다시 시도해주세요.
                                </p>
                            </section>
                        )}

                        {sakeMutation.isSuccess && (
                            <section className={"mt-10"}>
                                <div className={"mb-5"}>
                                    <h2 className={"text-2xl font-bold text-gray-900"}>
                                        &apos;{sakeMutation.data.food}&apos;과 어울리는 사케
                                    </h2>

                                    <p className={"mt-1 text-sm text-gray-500"}>
                                        AI가 추천한 3가지 사케입니다.
                                    </p>
                                </div>

                                <div className={"grid gap-6 md:grid-cols-2 lg:grid-cols-3"}>
                                    {sakeMutation.data.recommendations.map((sake)=>(
                                        <Link key={sake.no} href={`/sake/${sake.no}`}
                                              className={"group overflow-hidden rounded-2xl border border-gray-200 bg-white transition hover:-translate-y-1 hover:shadow-lg"}>
                                            <div className={"flex h-72 items-center justify-center bg-gray-50 p-6"}>
                                                <img src={sake.imageUrl || "/images/sake-placeholder.png"}
                                                     alt={sake.nameKo || sake.nameJa}
                                                     className={"h-full w-full object-contain transition group-hover:scale-105"}/>
                                            </div>

                                            <div className={"p-5"}>
                                                <div className={"mb-4"}>
                                                    <h3 className={"text-lg font-bold text-gray-900"}>
                                                        {sake.nameKo || sake.nameJa}
                                                    </h3>

                                                    {sake.nameKo && (
                                                        <p className={"mt-1 text-sm text-gray-400"}>
                                                            {sake.nameJa}
                                                        </p>
                                                    )}

                                                    {sake.breweryNameKo && (
                                                        <p className={"mt-2 text-sm text-gray-500"}>
                                                            {sake.breweryNameKo}
                                                        </p>
                                                    )}
                                                </div>

                                                <div className={"mb-4 flex flex-wrap gap-2"}>
                                                    {sake.sakeType && (
                                                        <span className={"rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-600"}>
                                                            {sake.sakeType}
                                                        </span>
                                                    )}

                                                    {sake.prefecture && (
                                                        <span className={"rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-600"}>
                                                            {sake.prefecture}
                                                        </span>
                                                    )}

                                                    {sake.polishingRatio && (
                                                        <span className={"rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-600"}>
                                                            정미 {sake.polishingRatio}
                                                        </span>
                                                    )}
                                                </div>

                                                <div className={"border-t border-gray-100 pt-4"}>
                                                    <p className={"mb-1 text-xs font-semibold text-gray-400"}>
                                                        추천 이유
                                                    </p>

                                                    <p className={"text-sm leading-6 text-gray-700"}>
                                                        {sake.reason}
                                                    </p>
                                                </div>
                                            </div>
                                        </Link>
                                    ))}
                                </div>
                            </section>
                        )}
                    </>
                )}

                {mode==="food" && (
                    <>
                        <section className={"rounded-2xl border border-gray-200 bg-white p-6"}>
                            <label htmlFor={"sakeSearch"} className={"mb-2 block text-sm font-semibold text-gray-800"}>
                                사케
                            </label>

                            {hasInitialSake && initialSakeQuery.isPending ? (
                                <div className={"rounded-lg bg-gray-50 px-4 py-4 text-sm text-gray-500"}>
                                    사케 정보를 불러오는 중...
                                </div>
                            ) : (
                                <>
                                    <div className={"flex gap-3"}>
                                        <div className={"relative min-w-0 flex-1"}>
                                            <input id={"sakeSearch"} type={"text"}
                                                   value={selectedSake
                                                       ? sakeSearch
                                                       : initialSake
                                                           ? initialSake.nameKo || initialSake.nameJa
                                                           : sakeSearch}
                                                   onChange={(e)=>handleSakeSearchChange(e.target.value)}
                                                   onKeyDown={(e)=>{
                                                       if(e.key==="Enter" && currentSake){
                                                           handleFoodRecommend();
                                                       }
                                                   }}
                                                   placeholder={"사케 이름을 검색해주세요."}
                                                   disabled={foodMutation.isPending || hasInitialSake}
                                                   autoComplete={"off"}
                                                   className={"w-full rounded-lg border border-gray-300 px-4 py-3 outline-none transition focus:border-gray-500 disabled:bg-gray-50"}
                                            />

                                            {!hasInitialSake && !selectedSake && sakeSearch.trim().length>=2 && (
                                                <div className={"absolute left-0 right-0 top-full z-20 mt-2 max-h-80 overflow-y-auto rounded-xl border border-gray-200 bg-white shadow-lg"}>
                                                    {searchQuery.isFetching && (
                                                        <p className={"px-4 py-4 text-sm text-gray-500"}>
                                                            검색 중...
                                                        </p>
                                                    )}

                                                    {!searchQuery.isFetching && searchQuery.data?.length===0 && (
                                                        <p className={"px-4 py-4 text-sm text-gray-500"}>
                                                            검색 결과가 없습니다.
                                                        </p>
                                                    )}

                                                    {!searchQuery.isFetching && searchQuery.data?.map((sake)=>(
                                                        <button key={sake.no} type={"button"} onClick={()=>handleSakeSelect(sake)}
                                                                className={"flex w-full items-center gap-4 border-b border-gray-100 px-4 py-3 text-left transition last:border-b-0 hover:bg-gray-50"}>
                                                            <div className={"flex h-14 w-14 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-gray-50"}>
                                                                <img src={sake.imageUrl || "/images/sake-placeholder.png"}
                                                                     alt={sake.nameKo || sake.nameJa}
                                                                     className={"h-full w-full object-contain"}/>
                                                            </div>

                                                            <div className={"min-w-0"}>
                                                                <p className={"truncate font-semibold text-gray-900"}>
                                                                    {sake.nameKo || sake.nameJa}
                                                                </p>

                                                                {sake.nameKo && (
                                                                    <p className={"mt-1 truncate text-sm text-gray-400"}>
                                                                        {sake.nameJa}
                                                                    </p>
                                                                )}
                                                            </div>
                                                        </button>
                                                    ))}
                                                </div>
                                            )}
                                        </div>

                                        <button type={"button"} onClick={handleFoodRecommend}
                                                disabled={!currentSake || foodMutation.isPending}
                                                className={"shrink-0 rounded-lg bg-gray-900 px-6 py-3 font-semibold text-white transition hover:bg-gray-700 disabled:cursor-not-allowed disabled:bg-gray-300"}>
                                            {foodMutation.isPending ? "추천 중..." : "추천받기"}
                                        </button>
                                    </div>

                                    {currentSake && (
                                        <div className={"mt-4 flex items-center gap-4 rounded-xl bg-gray-50 p-4"}>
                                            <div className={"flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-white"}>
                                                <img src={currentSake.imageUrl || "/images/sake-placeholder.png"}
                                                     alt={currentSake.nameKo || currentSake.nameJa}
                                                     className={"h-full w-full object-contain"}/>
                                            </div>

                                            <div>
                                                <p className={"text-sm font-medium text-gray-500"}>
                                                    선택한 사케
                                                </p>

                                                <p className={"mt-1 font-bold text-gray-900"}>
                                                    {currentSake.nameKo || currentSake.nameJa}
                                                </p>

                                                {currentSake.nameKo && (
                                                    <p className={"mt-1 text-sm text-gray-400"}>
                                                        {currentSake.nameJa}
                                                    </p>
                                                )}
                                            </div>
                                        </div>
                                    )}

                                    {hasInitialSake && initialSakeQuery.isError && (
                                        <p className={"mt-4 text-sm text-red-600"}>
                                            사케 정보를 불러오지 못했습니다.
                                        </p>
                                    )}
                                </>
                            )}
                        </section>

                        {foodMutation.isPending && (
                            <section className={"mt-8 rounded-2xl border border-gray-200 bg-gray-50 px-6 py-12 text-center"}>
                                <div className={"mx-auto mb-4 h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-gray-800"}/>

                                <p className={"font-semibold text-gray-800"}>
                                    이 사케와 잘 어울리는 음식을 찾고 있어요.
                                </p>

                                <p className={"mt-2 text-sm text-gray-500"}>
                                    처음 추천하는 사케는 시간이 조금 걸릴 수 있어요.
                                </p>
                            </section>
                        )}

                        {foodMutation.isError && (
                            <section className={"mt-8 rounded-2xl border border-red-200 bg-red-50 p-6"}>
                                <p className={"font-semibold text-red-700"}>
                                    추천을 불러오지 못했습니다.
                                </p>

                                <p className={"mt-1 text-sm text-red-600"}>
                                    잠시 후 다시 시도해주세요.
                                </p>
                            </section>
                        )}

                        {foodMutation.isSuccess && (
                            <section className={"mt-10"}>
                                <div className={"mb-5"}>
                                    <h2 className={"text-2xl font-bold text-gray-900"}>
                                        {currentSake?.nameKo || currentSake?.nameJa}와 어울리는 음식
                                    </h2>

                                    <p className={"mt-1 text-sm text-gray-500"}>
                                        AI가 추천한 음식입니다.
                                    </p>
                                </div>

                                <div className={"grid gap-6 md:grid-cols-3"}>
                                    {foodMutation.data.recommends.map((recommendedFood,index)=>(
                                        <article key={`${recommendedFood.name}-${index}`}
                                                 className={"rounded-2xl border border-gray-200 bg-white p-6"}>
                                            <div className={"mb-5 flex h-10 w-10 items-center justify-center rounded-full bg-gray-900 text-sm font-bold text-white"}>
                                                {index+1}
                                            </div>

                                            <h3 className={"text-xl font-bold text-gray-900"}>
                                                {recommendedFood.name}
                                            </h3>

                                            <div className={"mt-5 border-t border-gray-100 pt-4"}>
                                                <p className={"mb-1 text-xs font-semibold text-gray-400"}>
                                                    추천 이유
                                                </p>

                                                <p className={"text-sm leading-6 text-gray-700"}>
                                                    {recommendedFood.reason}
                                                </p>
                                            </div>
                                        </article>
                                    ))}
                                </div>
                            </section>
                        )}
                    </>
                )}
            </main>
        </>
    );
}