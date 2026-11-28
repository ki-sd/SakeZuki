import type { Metadata } from "next";
import SubmissionDetail from "@/components/submissions/SubmissionDetail";
import SubmissionShell from "@/components/submissions/SubmissionShell";

export const metadata: Metadata = { title: "사케 제보 상세 | SakeZuki" };

export default async function SubmissionPage({ params }: { params: Promise<{ id: string }> }) {
    return <SubmissionShell title="사케 제보 상세"><SubmissionDetail id={(await params).id} /></SubmissionShell>;
}
