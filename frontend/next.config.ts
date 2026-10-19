import type { NextConfig } from "next";

const nextConfig: NextConfig = {
    distDir: process.env.AUTH_TEST_DIST_DIR ?? ".next",
    // Docker 실행 이미지는 빌드 결과의 standalone 서버만 복사해 사용한다.
    output:"standalone",
    experimental:{
        proxyTimeout:150000
    },
    async rewrites() {
        // 개발 중 브라우저는 같은 출처의 /api를 호출하고, Next.js가 이를 로컬 Spring 서버로 전달한다.
        // 배포 환경에서는 앞단 Nginx가 /api를 분기하므로 여기서는 rewrite를 두지 않는다.
        if(process.env.NODE_ENV!=="development") {
            return [];
        }

        const backendOrigin = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";

        return [
            {
                source:"/oauth2/:path*",
                destination:`${backendOrigin}/oauth2/:path*`
            },
            {
                source:"/login/oauth2/:path*",
                destination:`${backendOrigin}/login/oauth2/:path*`
            },
            {
                source:"/api/:path*",
                destination:`${backendOrigin}/api/:path*`
            }
        ];
    }
};

export default nextConfig;
