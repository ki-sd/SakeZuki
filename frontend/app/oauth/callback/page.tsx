"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import Header from "@/components/layout/Header";
import { completeOAuth, errorMessage, finishDestination } from "@/app/api/auth";

export default function OAuthCallback() {
    const router = useRouter();
    const [error, setError] = useState("");
    useEffect(() => {
        let active = true;
        completeOAuth().then(() => { if (active) router.replace(finishDestination()); })
            .catch(error => { if (active) setError(errorMessage(error)); });
        return () => { active = false; };
    }, [router]);
    return <><Header /><main className="page-shell py-16"><div className="ui-state" role="status">
        {error ? <><p role="alert">{error}</p><Link href="/login" className="mt-5 inline-block underline">다시 로그인</Link></> : "Google 로그인을 완료하고 있습니다…"}
    </div></main></>;
}
