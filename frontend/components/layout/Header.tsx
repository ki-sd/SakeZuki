"use client";

import Link from "next/link";
import {usePathname} from "next/navigation";

export default function Header (){
    // usePathname은 현재 브라우저 경로를 읽어 메뉴의 활성 상태를 바꾼다.
    // 이 값과 링크의 강조가 이동 직후 다시 렌더링되어야 하므로 Client Component다.
    const pathname=usePathname();
    return (
        <header className={"border-b border-stone-200 bg-white"}>
            <div className={"page-shell flex h-16 items-center justify-between gap-4"}>
                <Link href={"/sake"} className={"shrink-0 rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700"}>
                    <img src={"/images/logo.png"} alt={"SakeZuki"} className={"h-10 w-auto object-contain sm:h-11"}/>
                </Link>

                <nav aria-label={"주 메뉴"} className={"flex h-full items-center gap-4 text-sm font-medium sm:gap-7"}>
                    <Link href={"/sake"} aria-current={pathname.startsWith("/sake") ? "page" : undefined}
                          className={`flex h-full items-center border-b-2 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${pathname.startsWith("/sake") ? "border-stone-800 text-stone-900" : "border-transparent text-stone-500 hover:text-stone-900"}`}>
                        사케 목록
                    </Link>
                    <Link href={"/recommend"} aria-current={pathname.startsWith("/recommend") ? "page" : undefined}
                          className={`flex h-full items-center border-b-2 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-700 ${pathname.startsWith("/recommend") ? "border-stone-800 text-stone-900" : "border-transparent text-stone-500 hover:text-stone-900"}`}>
                        페어링
                    </Link>
                </nav>
            </div>
        </header>
    )
}
