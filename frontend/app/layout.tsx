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
  description: "사케 검색 및 음식 페어링 서비스",
};

// App Router의 최상위 layout은 모든 페이지를 감싼다. 여기서는 서버에서 문서 뼈대와
// 메타데이터를 구성하고, 브라우저 상태가 필요한 QueryClient는 아래 Providers 경계에 맡긴다.
export default function RootLayout({ children, }: Readonly<{children:React.ReactNode;}>) {
  return (
    <html lang="ko" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="min-h-full flex flex-col">
        <Providers>
          {children}
        </Providers>
      </body>
    </html>
  );
}
