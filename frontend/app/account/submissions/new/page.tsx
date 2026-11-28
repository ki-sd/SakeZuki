import type { Metadata } from "next";
import NewSubmission from "@/components/submissions/NewSubmission";
import SubmissionShell from "@/components/submissions/SubmissionShell";

export const metadata: Metadata = { title: "새 사케 제보 | SakeZuki" };

export default function NewSubmissionPage() {
    return <SubmissionShell title="새 사케 제보"><NewSubmission /></SubmissionShell>;
}
