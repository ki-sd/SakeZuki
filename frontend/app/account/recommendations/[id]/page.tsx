import type { Metadata } from "next";
import HistoryDetail from "@/components/recommend/HistoryDetail";
export const metadata: Metadata = { title: "내 추천 기록 | SakeZuki", robots: { index: false, follow: false } };
export default async function HistoryPage({ params }: { params: Promise<{ id: string }> }) {
    return <HistoryDetail id={(await params).id} />;
}
