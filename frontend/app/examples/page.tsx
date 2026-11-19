import type { Metadata } from "next";
import Link from "next/link";
import Header from "@/components/layout/Header";
import ExampleBrowser from "@/components/recommend/ExampleBrowser";

export const metadata: Metadata = { title: "무료 페어링 예시 | SakeZuki", description: "로그인 없이 음식과 사케 양방향 페어링의 화면과 제품 정보를 살펴보세요. 직접 작성한 편집 예시이며 AI 호출과 크레딧 차감이 없습니다." };
export default function ExamplesPage() {
    return <><Header /><main id="main-content" className="page-shell space-y-9 py-10 sm:py-14">
        <header className="max-w-2xl"><p className="text-sm font-medium text-stone-500">로그인 없이 둘러보기</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">페어링 예시</h1>
            <p className="mt-5 text-base leading-8 text-stone-600">어떤 음식에서 사케를 고를지, 고른 사케와 무엇을 먹을지 확인해 보세요. 현재는 실제 제품을 바탕으로 작성한 편집 샘플을 제공합니다.</p></header>
        <ExampleBrowser />
        <aside className="ui-panel flex flex-wrap items-center justify-between gap-5 p-6"><p className="max-w-2xl text-sm leading-7 text-stone-600">자유 입력 AI 추천은 정식 서비스 준비 중입니다. 공개 예시에서는 새로운 추천을 생성하지 않습니다.</p>
            <div className="flex flex-wrap gap-3"><Link className="ui-button" href="/sake">사케 탐색하기</Link><Link className="ui-button-secondary" href="/account">회원 이용 안내</Link></div></aside>
    </main></>;
}
