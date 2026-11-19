import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import Providers from "@/app/providers";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "SakeZuki",
  description: "실제 사케 제품을 검색하고 음식과 사케의 양방향 페어링 예시를 살펴보는 서비스입니다.",
};

// App Router의 최상위 layout은 모든 페이지를 감싼다. 여기서는 서버에서 문서 뼈대와
// 메타데이터를 구성하고, 브라우저 상태가 필요한 QueryClient는 아래 Providers 경계에 맡긴다.
export default function RootLayout({ children, }: Readonly<{children:React.ReactNode;}>) {
  return (
    <html lang="ko" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="min-h-full flex flex-col">
        <a href="#main-content" className="sr-only z-50 rounded bg-white p-3 text-stone-900 focus:not-sr-only focus:fixed focus:left-3 focus:top-3">본문으로 건너뛰기</a>
        <Providers>
          {children}
        </Providers>
      </body>
    </html>
  );
}
