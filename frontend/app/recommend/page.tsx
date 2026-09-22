"use client";

// 입력·자동완성 선택·추천 요청은 브라우저 이벤트에 따라 달라져 Client Component가 필요하다.
import {Suspense,useState} from "react";
import Link from "next/link";
import {useSearchParams} from "next/navigation";
import {useMutation,useQuery} from "@tanstack/react-query";
import {recommendFood,recommendSake} from "@/app/api/recommend";
import {getSakeDetail,searchSake} from "@/app/api/sake";
import type {SakeSearchItem} from "@/types/sake";
import Header from "@/components/layout/Header";
import SakeImage from "@/components/sake/SakeImage";
import {getSakeTypeLabel} from "@/commons/sake";

// 화면의 두 추천 방향만 허용해 모드별 입력과 결과 렌더링이 같은 기준을 쓰게 한다.
type RecommendMode="sake" | "food";

export default function RecommendPage(){
    // useSearchParams를 쓰는 하위 화면을 Suspense로 감싸 URL 매개변수 해석 중 보여줄 UI를 둔다.
	return (
		<Suspense fallback={<><Header/><main className={"page-shell py-10"}><div className={"ui-state"}>추천 화면을 불러오는 중...</div></main></>}>
			<RecommendContent/>
		</Suspense>
	);
}

function RecommendContent(){
    // 상세 페이지에서 ?sakeNo=...로 들어오면 이미 고른 제품을 이어받아 음식 추천 모드로 연다.
	const searchParams=useSearchParams();
	const sakeNoParam=searchParams.get("sakeNo");
	const initialSakeNo=sakeNoParam ? Number(sakeNoParam) : null;
	const hasInitialSake=initialSakeNo!==null && Number.isInteger(initialSakeNo) && initialSakeNo>0;

    // 입력값·선택값·현재 모드는 화면에서만 쓰는 클라이언트 상태다.
    // API 응답과 요청 상태는 아래 useQuery/useMutation이 별도로 관리한다.
	const [mode,setMode]=useState<RecommendMode>(hasInitialSake ? "food" : "sake");
	const [food,setFood]=useState("");
	const [sakeSearch,setSakeSearch]=useState("");
	const [selectedSake,setSelectedSake]=useState<SakeSearchItem | null>(null);

    // URL의 no가 유효할 때만 제품을 조회한다. 상세 화면과 같은 키라 기존 상세 캐시를 재사용할 수 있다.
	const initialSakeQuery=useQuery({
		queryKey:["sakeDetail",initialSakeNo],
		queryFn:()=>getSakeDetail(initialSakeNo!),
		enabled:hasInitialSake,
		retry:false
	});

    // 상세 응답을 자동완성 선택 결과와 같은 최소 형태로 맞춰 이후 요청 흐름을 공유한다.
	const initialSake:SakeSearchItem | null=initialSakeQuery.data ? {
		no:initialSakeQuery.data.no,
		nameKo:initialSakeQuery.data.nameKo,
		nameJa:initialSakeQuery.data.nameJa,
		imageUrl:initialSakeQuery.data.imageUrl
	} : null;

	const currentSake=selectedSake || initialSake;

	const selectedSakeDetailQuery=useQuery({
		queryKey:["sakeDetail",currentSake?.no],
		queryFn:()=>getSakeDetail(currentSake!.no),
		enabled:!!currentSake,
		staleTime:60000
	});

	const selectedSakeDetail=selectedSakeDetailQuery.data;

    // 추천은 사용자가 버튼을 눌렀을 때 시작하는 POST 작업이라 useMutation으로 대기·실패·성공을 관리한다.
    // 서버의 DB 영속 캐시와 별개로, 여기서는 현재 요청의 화면 상태를 다룬다.
	const sakeMutation=useMutation({
		mutationFn:recommendSake
	});

	const foodMutation=useMutation({
		mutationFn:recommendFood
	});

    // 입력 문자열이 캐시 키가 되고 30초 동안 같은 검색어의 결과를 재사용한다.
    // 2자 미만·다른 모드·이미 선택한 제품에서는 enabled로 자동완성 요청을 막는다.
	const searchQuery=useQuery({
		queryKey:["sakeSearch",sakeSearch],
		queryFn:()=>searchSake(sakeSearch.trim()),
		enabled:mode==="food" && sakeSearch.trim().length>=2 && !selectedSake && !hasInitialSake,
		staleTime:30000
	});

    // 입력을 정리한 뒤 요청하고, 진행 중 재클릭은 막아 같은 POST가 겹치지 않게 한다.
	const handleSakeRecommend=()=>{
		const value=food.trim();

		if(!value || sakeMutation.isPending){
			return;
		}

		sakeMutation.mutate({
			food:value
		});
	};

    // 표시된 문자열이 아니라 DB에서 선택한 sakeNo를 보내 제품 식별을 확정한다.
	const handleFoodRecommend=()=>{
		if(!currentSake || foodMutation.isPending){
			return;
		}

		foodMutation.mutate({
			sakeNo:currentSake.no
		});
	};

    // 새 제품을 고르면 이전 추천 결과를 지워 다른 제품의 결과가 남지 않게 한다.
	const handleSakeSelect=(sake:SakeSearchItem)=>{
		setSelectedSake(sake);
		setSakeSearch(sake.nameKo || sake.nameJa);
		foodMutation.reset();
	};

    // 선택 후 글자를 고치면 이전 no는 더 이상 입력과 맞지 않으므로 선택을 해제한다.
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

			<main className={"page-shell pb-16 pt-8 sm:pt-10"}>
				<section className={"mb-7"}>
					<h1 className={"text-2xl font-semibold tracking-tight text-stone-900"}>
						음식 페어링
					</h1>

					<p className={"mt-1.5 text-sm text-stone-500"}>
						음식이나 사케를 선택해 어울리는 조합을 찾아보세요.
					</p>
				</section>

				<div className={"mb-7 flex border-b border-stone-200"}>
					<button type={"button"} onClick={()=>handleModeChange("sake")} aria-pressed={mode==="sake"}
							className={`ui-tab ${
								mode==="sake"
									? "ui-tab-active"
									: ""
							}`}>
						음식으로 사케 찾기
					</button>

					<button type={"button"} onClick={()=>handleModeChange("food")} aria-pressed={mode==="food"}
							className={`ui-tab ${
								mode==="food"
									? "ui-tab-active"
									: ""
							}`}>
						사케로 음식 찾기
					</button>
				</div>

				{mode==="sake" && (
					<>
						<section className={"ui-panel max-w-3xl p-5 sm:p-6"}>
							<label htmlFor={"food"} className={"mb-2 block text-sm font-medium text-stone-800"}>
								음식
							</label>

							<div className={"flex flex-col gap-2 sm:flex-row"}>
								<input id={"food"} type={"text"} value={food}
									   onChange={(e)=>setFood(e.target.value)}
									   onKeyDown={(e)=>{
										   if(e.key==="Enter"){
											   handleSakeRecommend();
										   }
									   }}
									   placeholder={"예: 삼겹살, 스키야키, 회"} disabled={sakeMutation.isPending}
									   className={"ui-input w-full flex-1"}
								/>

								<button type={"button"} onClick={handleSakeRecommend}
										disabled={!food.trim() || sakeMutation.isPending}
										 className={"ui-button w-full sm:w-auto"}>
									{sakeMutation.isPending ? "추천 중..." : "추천받기"}
								</button>
							</div>
						</section>

                        {/* mutation 상태를 그대로 사용해 최초 생성처럼 오래 걸리는 요청도 대기·오류·결과를 구분한다. */}
						{sakeMutation.isPending && (
							<section className={"ui-state mt-7"}>
								<div className={"mx-auto mb-4 h-6 w-6 animate-spin rounded-full border-2 border-stone-200 border-t-stone-700"}/>

								<p className={"font-semibold text-gray-800"}>
									음식과 잘 어울리는 사케를 찾고 있어요.
								</p>

								<p className={"mt-2 text-sm text-gray-500"}>
									처음 추천하는 음식은 시간이 조금 걸릴 수 있어요.
								</p>
							</section>
						)}

						{sakeMutation.isError && (
							<section className={"mt-7 rounded-lg border border-red-200 bg-red-50 p-5"}>
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
									<h2 className={"break-words text-xl font-semibold text-stone-900 [overflow-wrap:anywhere]"}>
										&apos;{sakeMutation.data.food}&apos;과 어울리는 사케
									</h2>

									<p className={"mt-1 text-sm text-stone-500"}>
										어울리는 이유를 함께 확인해 보세요.
									</p>
								</div>

								{sakeMutation.data.recommendations.length===0 && (
									<div className={"ui-state"}>추천 결과가 없습니다. 다른 음식으로 다시 찾아보세요.</div>
								)}

								<div className={"grid gap-4 sm:grid-cols-2 lg:grid-cols-3"}>
									{sakeMutation.data.recommendations.map((sake)=>(
										<Link key={sake.no} href={`/sake/${sake.no}`}
											  className={"group min-w-0 overflow-hidden rounded-lg border border-stone-200 bg-white transition-colors hover:border-stone-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
											<div className={"flex h-56 items-center justify-center bg-stone-100 p-5"}>
												<img src={sake.imageUrl || "/images/sake-placeholder.png"}
													 alt={sake.nameKo || sake.nameJa}
													 className={"h-full w-full object-contain"}/>
											</div>

											<div className={"p-4 sm:p-5"}>
												<div className={"mb-4"}>
													<h3 className={"break-words text-base font-semibold leading-6 text-stone-900 [overflow-wrap:anywhere]"}>
														{sake.nameKo || sake.nameJa}
													</h3>

													{sake.nameKo && (
														<p className={"mt-1 break-words text-xs text-stone-500 [overflow-wrap:anywhere]"}>
															{sake.nameJa}
														</p>
													)}

													{sake.breweryNameKo && (
														<p className={"mt-2 text-sm text-gray-500"}>
															{sake.breweryNameKo}
														</p>
													)}
												</div>

												<div className={"mb-4 flex flex-wrap gap-x-3 gap-y-1 text-xs text-stone-600"}>
													{sake.sakeType && (
														<span>
															{sake.sakeType}
														</span>
													)}

													{sake.prefecture && (
														<span>
															{sake.prefecture}
														</span>
													)}

													{sake.polishingRatio && (
														<span>
															정미 {sake.polishingRatio}
														</span>
													)}
												</div>

												<div className={"border-t border-stone-100 pt-4"}>
													<p className={"mb-1 text-xs font-medium text-stone-500"}>
														추천 이유
													</p>

													<p className={"break-words text-sm leading-6 text-stone-700 [overflow-wrap:anywhere]"}>
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
						<section className={"ui-panel max-w-3xl p-5 sm:p-6"}>
							<label htmlFor={"sakeSearch"} className={"mb-2 block text-sm font-medium text-stone-800"}>
								사케
							</label>

							{hasInitialSake && initialSakeQuery.isPending ? (
								<div className={"rounded-md bg-stone-100 px-4 py-4 text-sm text-stone-500"}>
									사케 정보를 불러오는 중...
								</div>
							) : (
								<>
									<div className={"flex flex-col gap-2 sm:flex-row"}>
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
												   className={"ui-input w-full"}
											/>

											{!hasInitialSake && !selectedSake && sakeSearch.trim().length>=2 && (
												<div className={"absolute left-0 right-0 top-full z-20 mt-1 max-h-80 overflow-y-auto rounded-lg border border-stone-200 bg-white shadow-sm"}>
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

                                                    {/* 검색 결과의 no를 key와 선택 식별자로 함께 사용한다. */}
													{!searchQuery.isFetching && searchQuery.data?.map((sake)=>(
														<button key={sake.no} type={"button"} onClick={()=>handleSakeSelect(sake)}
																className={"flex w-full items-center gap-3 border-b border-stone-100 px-3 py-2.5 text-left transition last:border-b-0 hover:bg-stone-50 focus-visible:outline-2 focus-visible:outline-stone-700"}>
															<div className={"relative flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-md bg-stone-100"}>
                                                                <SakeImage src={sake.imageUrl}
                                                                           alt={sake.nameKo || sake.nameJa}/>
															</div>

															<div className={"min-w-0"}>
																<p className={"truncate text-sm font-medium text-stone-900"}>
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
												className={"ui-button w-full sm:w-auto"}>
											{foodMutation.isPending ? "추천 중..." : "추천받기"}
										</button>
									</div>

									{currentSake && (
										<div className={"group relative mt-4 border-t border-stone-200 pt-4"}>
											<Link href={`/sake/${currentSake.no}`}
											      className={"flex min-w-0 items-center gap-3 rounded-md p-1 transition hover:bg-stone-50 focus-visible:outline-2 focus-visible:outline-stone-700"}>
												<div className={"relative h-14 w-14 shrink-0 overflow-hidden rounded-md bg-stone-100"}>
													<SakeImage src={currentSake.imageUrl} alt={currentSake.nameKo || currentSake.nameJa}/>
												</div>

												<div className={"min-w-0"}>
													<p className={"text-sm font-medium text-gray-500"}>
														선택한 사케
													</p>

													<p className={"break-words text-sm font-semibold text-stone-900 [overflow-wrap:anywhere]"}>
														{currentSake.nameKo || currentSake.nameJa}
													</p>

													{currentSake.nameKo && (
														<p className={"mt-1 break-words text-xs text-stone-500 [overflow-wrap:anywhere]"}>
															{currentSake.nameJa}
														</p>
													)}
												</div>
											</Link>

											{selectedSakeDetail && (
												<div className={"pointer-events-none absolute left-0 top-full z-30 mt-2 hidden w-72 rounded-lg border border-stone-200 bg-white p-4 shadow-lg group-hover:block group-focus-within:block"}>
													<p className={"mb-3 text-sm font-semibold text-stone-900"}>
														간단 정보
													</p>

													<div className={"grid grid-cols-2 gap-x-4 gap-y-2 text-sm"}>
														<span className={"text-stone-500"}>종류</span>
														<span className={"text-stone-800"}>
															{selectedSakeDetail.sakeType ? getSakeTypeLabel(selectedSakeDetail.sakeType) : "-"}
                    									</span>

														<span className={"text-stone-500"}>니혼슈도</span>
														<span className={"text-stone-800"}>
															{selectedSakeDetail.sakeMeterValue || "-"}
														</span>

														<span className={"text-stone-500"}>정미보합</span>
														<span className={"text-stone-800"}>
															{selectedSakeDetail.polishingRatio || "-"}
														</span>

														<span className={"text-stone-500"}>알코올</span>
														<span className={"text-stone-800"}>
															{selectedSakeDetail.alcoholPercentage || "-"}
														</span>
													</div>

													<p className={"mt-3 border-t border-stone-100 pt-3 text-xs text-stone-400"}>
														클릭하면 상세 정보를 볼 수 있습니다.
													</p>
												</div>
											)}
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
							<section className={"ui-state mt-7"}>
								<div className={"mx-auto mb-4 h-6 w-6 animate-spin rounded-full border-2 border-stone-200 border-t-stone-700"}/>

								<p className={"font-semibold text-gray-800"}>
									이 사케와 잘 어울리는 음식을 찾고 있어요.
								</p>

								<p className={"mt-2 text-sm text-gray-500"}>
									처음 추천하는 사케는 시간이 조금 걸릴 수 있어요.
								</p>
							</section>
						)}

						{foodMutation.isError && (
							<section className={"mt-7 rounded-lg border border-red-200 bg-red-50 p-5"}>
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
									<h2 className={"break-words text-xl font-semibold text-stone-900 [overflow-wrap:anywhere]"}>
										{currentSake?.nameKo || currentSake?.nameJa}와 어울리는 음식
									</h2>

									<p className={"mt-1 text-sm text-stone-500"}>
										어울리는 이유를 함께 확인해 보세요.
									</p>
								</div>

								{foodMutation.data.recommends.length===0 && (
									<div className={"ui-state"}>추천 결과가 없습니다. 다른 사케로 다시 찾아보세요.</div>
								)}

								<div className={"grid gap-4 md:grid-cols-3"}>
									{foodMutation.data.recommends.map((recommendedFood,index)=>(
										<article key={`${recommendedFood.name}-${index}`}
												 className={"min-w-0 rounded-lg border border-stone-200 bg-white p-5"}>
											<div className={"mb-3 text-xs tabular-nums text-stone-500"}>
												{index+1}
											</div>

											<h3 className={"break-words text-lg font-semibold text-stone-900 [overflow-wrap:anywhere]"}>
												{recommendedFood.name}
											</h3>

											<div className={"mt-4 border-t border-stone-100 pt-4"}>
												<p className={"mb-1 text-xs font-medium text-stone-500"}>
													추천 이유
												</p>

												<p className={"break-words text-sm leading-6 text-stone-700 [overflow-wrap:anywhere]"}>
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
