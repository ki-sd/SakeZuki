"use client";

import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
import {useState} from "react";

// TanStack Query를 앱 전체에서 사용할 수 있도록 전역설정
export default function Providers({children}:{children:React.ReactNode}){
    const [queryClient]=useState(()=>new QueryClient());

    return (
        <QueryClientProvider client={queryClient}>
            {children}
        </QueryClientProvider>
    )
}