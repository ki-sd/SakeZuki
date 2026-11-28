"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listSubmissions, submissionError, submissionsPath } from "@/app/api/submissions";
import { useAuth } from "@/components/auth/AuthProvider";
import { statusLabels, submissionDate } from "./SubmissionShell";

export default function SubmissionList() {
    const { member } = useAuth();
    const [page, setPage] = useState(0);
    const result = useQuery({ queryKey: ["my-submissions", member?.memberNo, page],
        queryFn: ({ signal }) => listSubmissions(page,signal), retry: false,
        refetchInterval: query => query.state.data?.items.some(item => item.approvalInProgress) ? 15000 : false });
    if (result.isPending) return <p role="status" className="ui-state">제보를 불러오는 중…</p>;
    if (result.isError) return <div role="alert" className="ui-state">{submissionError(result.error)}{" "}
        <button className="underline" onClick={() => void result.refetch()}>다시 불러오기</button></div>;
    return <section className="ui-panel p-5 sm:p-7" aria-label="제보 목록">
        {result.data.items.length === 0 ? <p className="py-5 text-sm text-stone-600">이 페이지에 제보가 없습니다.</p> :
            <ul className="divide-y divide-stone-200">{result.data.items.map(item => <li className="py-4" key={item.id}>
                <Link href={`${submissionsPath}/${item.id}`} className="block rounded p-2 hover:bg-stone-50">
                    <p className="text-sm font-medium text-stone-600">{statusLabels[item.status]}{item.approvalInProgress && " · 이미지 승인 진행 중"}</p>
                    <p className="mt-1 break-words text-lg font-semibold">{item.nameKo || item.nameJa || "사케 제보"}</p>
                    {item.nameJa && item.nameKo && <p className="break-words text-sm text-stone-600" lang="ja">{item.nameJa}</p>}
                    <p className="mt-2 text-sm text-stone-600">제보 {submissionDate(item.createdAt)}</p>
                </Link>
            </li>)}</ul>}
        {(page > 0 || result.data.hasMore) && <nav className="mt-4 flex flex-wrap items-center gap-3 text-sm" aria-label="제보 목록 페이지">
            <button className="ui-button-secondary disabled:opacity-40" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
            <span>{page + 1}페이지</span>
            <button className="ui-button-secondary disabled:opacity-40" disabled={!result.data.hasMore} onClick={() => setPage(page + 1)}>다음</button>
        </nav>}
    </section>;
}
