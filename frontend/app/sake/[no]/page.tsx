"use client";

// 동적 경로의 no를 훅으로 읽고 탭을 클릭해 바꾸므로 브라우저에서 상태를 관리한다.
import {useState} from "react";
import {useParams} from "next/navigation";
import {useQuery} from "@tanstack/react-query";
import {getSakeDetail} from "@/app/api/sake";
import Header from "@/components/layout/Header";
import Link from "next/link";
import {getSakeTypeLabel,getPrefectureLabel} from "@/commons/sake";
import BreweryMap from "@/components/brewery/BreweryMap";

// 허용하는 탭을 세 값으로 제한해 잘못된 문자열이 상태나 비교식에 들어오지 않게 한다.
type DetailTab="sake" | "brand" | "brewery";

export default function SakeDetailPage(){
    // App Router의 [no] 구간은 문자열이므로 API 식별자로 보내기 전에 숫자로 바꾼다.
    // [no]값 갖고옴
    const params=useParams<{no:string}>();
    const no=Number(params.no);

    // 현재 선택된 탭
    const [activeTab,setActiveTab]=useState<DetailTab>("sake");

    // 목록 카드·추천 화면과 같은 sakeDetail 키를 써 동일한 제품 상세 응답을 캐시에서 재사용한다.
    // 잘못된 경로 값이면 enabled가 요청을 막아 유효하지 않은 번호를 서버에 보내지 않는다.
    // 상세 조회
    const {data,isLoading,isError}=useQuery({
        queryKey:["sakeDetail",no],
        queryFn:()=>getSakeDetail(no),
        enabled:Number.isInteger(no) && no>0,
        retry:false
    });

    // 서버 상태에 따라 초기 대기·실패·성공 화면을 분리한다. 성공 전에는 상세 필드를 읽지 않는다.
    if(isLoading){
        return (
            <>
                <Header/>
                <main className={"page-shell py-10"}>
                    <div className={"ui-panel grid animate-pulse gap-6 p-5 sm:grid-cols-[minmax(0,280px)_1fr]"} aria-label={"사케 정보 불러오는 중"}>
                        <div className={"aspect-4/5 rounded bg-stone-100"}/>
                        <div className={"space-y-4 py-4"}><div className={"h-6 w-2/3 rounded bg-stone-100"}/><div className={"h-4 w-1/2 rounded bg-stone-100"}/></div>
                    </div>
                </main>
            </>
        )
    }
    if(isError || !data) {
        return (
            <>
                <Header/>
                <main className={"page-shell py-10"}>
                    <div className={"ui-state"}>정보를 불러오지 못했습니다.</div>
                </main>
            </>
        );
    }
    return (
        <>
            <Header/>

            <main className={"page-shell pb-16 pt-7 sm:pt-9"}>
                <Link href="/sake"
                    className={"mb-6 inline-flex items-center gap-2 rounded-sm text-sm font-medium text-stone-500 transition hover:text-stone-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
                    <span>←</span>
                    <span>목록으로 돌아가기</span>
                </Link>
                {/* 사케 기본정보 */}
                <section className={"grid gap-7 sm:grid-cols-[minmax(0,280px)_1fr] sm:gap-9 lg:grid-cols-[minmax(0,340px)_1fr] lg:gap-12"}>
                    <div className={"overflow-hidden rounded-lg border border-stone-200 bg-white"}>
                        <div className={"aspect-4/5 bg-stone-100 p-5 sm:aspect-auto sm:h-full sm:min-h-88"}>
                            <img src={data.imageUrl || "/images/sake-placeholder.png"}
                                alt={data.nameKo || data.nameJa} className={"h-full w-full object-contain"}/>
                        </div>
                    </div>

                    <div className={"flex min-w-0 flex-col justify-center"}>
                        <p className={"text-sm font-medium text-stone-700"}>
                            {getSakeTypeLabel(data.sakeType)}
                        </p>

                        {data.sakeType && (
                            <p className={"mt-1 text-xs text-stone-500"}>
                                {data.sakeType}
                            </p>
                        )}

                        <h1 className={"mt-2 break-words text-2xl font-semibold leading-tight tracking-tight text-stone-900 sm:text-3xl [overflow-wrap:anywhere]"}>
                            {data.nameKo || data.nameJa}
                        </h1>

                        {data.nameKo && (
                            <p className={"mt-2 break-words text-sm leading-6 text-stone-600 [overflow-wrap:anywhere]"}>
                                {data.nameJa}
                            </p>
                        )}

                        {data.nameKana && (
                            <p className={"mt-1 text-xs text-stone-500"}>
                                {data.nameKana}
                            </p>
                        )}

                        <div className={"mt-5 flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-stone-600"}>
                            {data.brewery?.prefecture && (
                                <span>
                                    {getPrefectureLabel(data.brewery.prefecture)}
                                </span>
                            )}

                            {data.brand?.nameJa && (
                                <span>
                                    {data.brand.nameKo || data.brand.nameJa}
                                </span>
                            )}

                            {data.brewery?.nameJa && (
                                <span>
                                    {data.brewery.nameKo || data.brewery.nameJa}
                                </span>
                            )}
                        </div>
                        <Link href={`/recommend?sakeNo=${data.no}`}
                            className={"ui-button-secondary mt-6 w-fit max-w-full text-center"}>
                            이 사케와 어울리는 음식 찾기
                        </Link>
                    </div>

                </section>

                {/* 탭은 클라이언트 상태만 바꾸므로 사케·브랜드·양조장을 전환해도 상세 API를 다시 호출하지 않는다. */}
                {/* 상세정보 탭 */}
                <section className={"mt-10 sm:mt-12"}>
                    <div className={"flex border-b border-stone-200"}>
                        <button type="button" onClick={()=>setActiveTab("sake")}
                            className={`ui-tab ${
                                activeTab==="sake"
                                    ? "ui-tab-active"
                                    : ""
                                }`
                            }>
                            사케 정보
                        </button>

                        <button type="button" onClick={()=>setActiveTab("brand")}
                            className={`ui-tab ${
                                activeTab==="brand"
                                    ? "ui-tab-active"
                                    : ""
                                }`
                            }>
                            브랜드
                        </button>

                        <button type="button" onClick={()=>setActiveTab("brewery")}
                            className={`ui-tab ${
                                activeTab==="brewery"
                                    ? "ui-tab-active"
                                    : ""
                                }`
                            }>
                            양조장
                        </button>
                    </div>

                    <div className={"py-7"}>
                        {/* 사케 정보 */}
                        {activeTab==="sake" && (
                            <div className={"grid gap-x-10 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
                                <InfoItem label="종류"
                                    value={getSakeTypeLabel(data.sakeType)}
                                    subValue={data.sakeType}/>
                                <InfoItem label="사용 쌀"
                                    value={data.riceKo || data.rice}
                                    subValue={data.riceKo ? data.rice : null}
                                />
                                <InfoItem label="정미보합"
                                    value={data.polishingRatio}
                                />
                                <InfoItem label="효모"
                                    value={data.yeastKo || data.yeast}
                                    subValue={data.yeastKo ? data.yeast : null}
                                />
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
                                    <div className={"grid gap-x-10 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
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
                            <>
                                <div className={"grid gap-x-10 gap-y-6 sm:grid-cols-2 lg:grid-cols-3"}>
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
                                            <p className={"text-xs text-stone-500"}>
                                                홈페이지
                                            </p>

                                            <a href={data.brewery.website} target="_blank" rel="noopener noreferrer"
                                               className={"mt-1 inline-block break-all font-medium text-stone-900 underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-stone-700"}>
                                                홈페이지 방문
                                            </a>
                                        </div>
                                    )}
                                </div>

                                {data.brewery.address && (
                                    <BreweryMap
                                        name={data.brewery.nameJa}
                                        address={data.brewery.address}
                                    />
                                )}
                            </>
                        )}
                    </div>
                </section>
            </main>
        </>
    );
}
// 상세 필드마다 null 가능성이 달라 공통 컴포넌트가 빈 값과 원문 병기를 일관되게 처리한다.
interface InfoItemProps{
    label:string;
    value:string | null | undefined;
    subValue?:string | null;
}

// 번역값이 있을 때만 원문을 보조 줄에 표시하고, 정보가 없으면 같은 문구로 안내한다.
// 공통 사용
function InfoItem({label,value,subValue}:InfoItemProps){
    return (
        <div>
            <p className={"text-xs text-stone-500"}>{label}</p>
            <p className={"mt-1 break-words text-sm font-medium leading-6 text-stone-900 [overflow-wrap:anywhere]"}>{value || "정보 없음"}</p>
            {subValue && value!==subValue && (
                <p className={"mt-0.5 break-words text-xs text-stone-500 [overflow-wrap:anywhere]"}>{subValue}</p>
            )}
        </div>
    );
}
// 정보 X
function EmptyInfo(){
    return (
        <div className={"ui-state"}>
            등록된 정보가 없습니다.
        </div>
    );
}
