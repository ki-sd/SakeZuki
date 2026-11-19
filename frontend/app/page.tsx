import type { Metadata } from "next";
import Link from "next/link";
import Header from "@/components/layout/Header";
import ExampleBrowser from "@/components/recommend/ExampleBrowser";

export const metadata: Metadata = {
    title: "SakeZuki | 음식과 사케, 취향을 찾아가는 페어링",
    description: "실제 사케 데이터에서 제품을 탐색하고 음식과 사케의 양방향 페어링을 살펴보세요. 사케 검색과 상세 조회, 공개 편집 예시는 무료로 이용할 수 있습니다.",
};
export default function Home() {
    // 첫 소개와 진입 링크는 API 상태와 무관하게 렌더링한다. 예시 조회 실패가 랜딩 전체를 막지 않는다.
    return <><Header /><main id="main-content">
        <section className="border-b border-stone-200 bg-[#f2efe8]">
            <div className="page-shell grid gap-12 py-16 sm:py-24 lg:grid-cols-[1.2fr_0.8fr] lg:items-center">
                <div><p className="text-xs font-semibold tracking-[0.2em] text-stone-600">SAKE · FOOD · DISCOVERY</p>
                    <h1 className="mt-6 text-4xl font-semibold leading-tight tracking-tight text-stone-900 sm:text-5xl">오늘의 음식에서,<br />나의 사케를 찾다.</h1>
                    <p className="mt-6 max-w-xl text-base leading-8 text-stone-600">이름과 종류로 사케를 살펴보고, 음식과 어울리는 조합을 발견하세요. 실제 제품 데이터 위에서 취향을 넓혀가는 SakeZuki입니다.</p>
                    <div className="mt-9 flex flex-wrap gap-3"><Link className="ui-button" href="/sake">사케 탐색하기</Link><Link className="ui-button-secondary" href="/examples">무료 페어링 예시 보기</Link></div>
                    <p className="mt-5 text-xs leading-6 text-stone-500">사케 검색·상세 조회와 공개 예시는 로그인 없이 이용할 수 있습니다.</p>
                </div>
                <div className="rounded-2xl border border-stone-300 bg-white/75 p-7 sm:p-9">
                    <p className="text-sm font-medium text-stone-500">두 방향으로 만나는 페어링</p>
                    <div className="mt-7 border-b border-stone-200 pb-7"><p className="text-xs text-stone-500">음식이 정해졌다면</p><h2 className="mt-2 text-2xl font-semibold">음식 → 사케</h2><p className="mt-3 text-sm leading-7 text-stone-600">먹고 싶은 음식에서 시작해 실제 사케 제품과 추천 이유를 살펴봅니다.</p></div>
                    <div className="pt-7"><p className="text-xs text-stone-500">사케를 골랐다면</p><h2 className="mt-2 text-2xl font-semibold">사케 → 음식</h2><p className="mt-3 text-sm leading-7 text-stone-600">제품을 먼저 고르고 함께 즐길 음식의 방향을 찾아봅니다.</p></div>
                </div>
            </div>
        </section>
        <section className="page-shell py-14 sm:py-20" aria-labelledby="features-title">
            <p className="text-xs font-semibold tracking-wider text-stone-500">EXPLORE WITH CONTEXT</p><h2 id="features-title" className="mt-3 text-2xl font-semibold sm:text-3xl">제품을 알고, 조합을 이해하는 즐거움</h2>
            <div className="mt-8 grid gap-4 md:grid-cols-3">
                {[["01", "실제 사케를 탐색", "제품명·종류·원료·양조장 정보를 한곳에서 확인합니다.", "/sake", "사케 목록 열기"],
                  ["02", "추천 방식 미리 보기", "공개 편집 예시로 양방향 페어링 화면을 먼저 살펴봅니다.", "/examples", "공개 예시 열기"],
                  ["03", "받았던 결과 다시 보기", "회원 추천은 당시 결과를 30일간 보관하고 무료로 다시 열람합니다.", "/account", "마이페이지 열기"]].map(([number,title,description,href,label]) =>
                    <article key={number} className="ui-panel flex flex-col p-6"><p aria-hidden="true" className="text-sm text-stone-400">{number}</p><h3 className="mt-5 text-lg font-semibold">{title}</h3><p className="mt-3 flex-1 text-sm leading-7 text-stone-600">{description}</p><Link href={href} className="mt-6 text-sm font-medium underline underline-offset-4">{label}</Link></article>)}
            </div>
            <div className="mt-8 rounded-lg bg-stone-100 p-6 text-sm leading-7 text-stone-600"><h3 className="font-semibold text-stone-800">AI가 제품을 지어내지 않도록</h3><p className="mt-2">음식에서 사케를 찾는 추천은 DB에서 실제 제품 후보를 조회한 뒤 AI가 후보를 고르는 구조입니다. 공용 캐시를 재사용해 외부 API 호출을 줄이고, 회원별 기록은 당시 결과의 스냅샷으로 따로 보관합니다.</p></div>
        </section>
        <section className="border-y border-stone-200 bg-white"><div className="page-shell space-y-8 py-14 sm:py-20">
            <div><p className="text-xs font-semibold tracking-wider text-stone-500">A TASTE OF PAIRING</p><h2 className="mt-3 text-2xl font-semibold sm:text-3xl">로그인 없이 살펴보는 페어링</h2><p className="mt-4 max-w-2xl text-sm leading-7 text-stone-600">현재 공개 자료는 시드의 실재 제품을 대조해 직접 작성한 편집 예시입니다. 실제 AI가 생성한 결과나 이용자 추천 기록으로 표시하지 않습니다.</p></div>
            <ExampleBrowser compact />
        </div></section>
        <section className="page-shell py-14 sm:py-20" aria-labelledby="availability-title">
            <h2 id="availability-title" className="text-2xl font-semibold">지금 이용할 수 있는 기능</h2>
            <div className="mt-6 grid gap-5 md:grid-cols-2"><div className="ui-panel p-6"><h3 className="font-semibold">바로 둘러보기</h3><p className="mt-3 text-sm leading-7 text-stone-600">사케 검색·상세정보와 무료 편집 예시를 공개합니다. 기존 계정으로 로그인하면 마이페이지에서 회원 상태와 기록을 확인할 수 있습니다.</p><div className="mt-5 flex flex-wrap gap-4 text-sm"><Link className="underline" href="/sake">사케 검색</Link><Link className="underline" href="/login">로그인</Link><Link className="underline" href="/account">마이페이지</Link></div></div>
                <div className="ui-panel p-6"><h3 className="font-semibold">정식 서비스 준비 중</h3><p className="mt-3 text-sm leading-7 text-stone-600">자유 입력 AI 추천은 본인확인·성인확인 완료 회원에게 제공하는 기능입니다. 현재 사업자 연동과 유료 충전은 준비 중이며, 공개 예시로 먼저 살펴볼 수 있습니다.</p><p className="mt-3 text-xs leading-6 text-stone-500">정식 추천은 캐시 사용 여부와 관계없이 1크레딧을 사용합니다. 개인 기록 재열람은 무료이며 새 요청 결과가 이전과 같을 수 있습니다.</p><Link className="mt-5 inline-block text-sm underline" href="/recommend">추천 이용 정책 확인</Link></div></div>
        </section>
    </main><footer className="border-t border-stone-200"><div className="page-shell flex flex-wrap justify-between gap-3 py-7 text-xs leading-6 text-stone-500"><span>SakeZuki · 사케 정보와 음식 페어링</span><span>주류를 판매하지 않습니다. 과도한 음주는 건강에 해롭습니다.</span></div></footer></>;
}
