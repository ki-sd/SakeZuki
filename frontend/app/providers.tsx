"use client";

import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
import {useState} from "react";

// QueryClientProvider가 하위 페이지에 서버 상태 캐시를 공유하므로 Client Component가 필요하다.
// useState의 초기화 함수로 QueryClient를 한 번만 만들어 다시 렌더링되어도 캐시가 유지되게 한다.
// TanStack Query를 앱 전체에서 사용할 수 있도록 전역설정
export default function Providers({children}:{children:React.ReactNode}){
    const [queryClient]=useState(()=>new QueryClient());

    return (
        <QueryClientProvider client={queryClient}>
            {children}
        </QueryClientProvider>
    )
}