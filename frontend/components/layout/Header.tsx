import Link from "next/link";

export default function Header (){
    return (
        <header className={"border-b border-gray-200 bg-white"}>
            <div className={"mx-auto flex h-16 max-w-7xl items-center justify-between px-6"}>
                <Link href={"/"} className={"text-xl font-bold text-gray-900"}>
                    <img src={"/images/logo.png"} alt={"SakeZuki"} className={"h-12 w-auto object-contain"}/>
                </Link>

                <nav className={"flex items-center gap-6 text-sm font-medium text-gray-600"}>
                    <Link href={"/sake"} className={"transition hover:text-gray-900"}>
                        사케
                    </Link>
                    <Link href={"/brewery"} className={"transition hover:text-gray-900"}>
                        양조장
                    </Link>
                    <Link href={"/like"} className={"transition hover:text-gray-900"}>
                        찜
                    </Link>
                </nav>
            </div>
        </header>
    )
}