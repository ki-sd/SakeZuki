import type { Metadata } from "next";
import SubmissionList from "@/components/submissions/SubmissionList";
import SubmissionShell from "@/components/submissions/SubmissionShell";

export const metadata: Metadata = { title: "내 사케 제보 | SakeZuki" };

export default function SubmissionsPage() {
    return <SubmissionShell title="내 사케 제보"><SubmissionList /></SubmissionShell>;
}
