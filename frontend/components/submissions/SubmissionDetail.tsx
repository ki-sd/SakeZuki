"use client";

import { useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { cancelSubmission, conflict, getSubmission, reviseSubmission, submissionError,
    type Choice, type Revision, type SubmissionInput } from "@/app/api/submissions";
import { useAuth, useSessionGuard } from "@/components/auth/AuthProvider";
import SubmissionForm, { rangeFields, textFields } from "./SubmissionForm";
import PrivatePreview from "./PrivatePreview";
import { statusLabels } from "./SubmissionShell";
import { submissionDate } from "@/commons/submission-date";

function choiceLabel(choice?: Choice | null) {
    if (!choice) return "없음";
    return choice.existingNo ? `기존 등록 번호 ${choice.existingNo}` : `${choice.proposed?.ko} · ${choice.proposed?.ja} (신규 제안)`;
}
function RevisionContent({ revision, id, historical = false }: { revision: Revision; id: string; historical?: boolean }) {
    const [showPreview, setShowPreview] = useState(!historical);
    const { data } = revision;
    const values = [
        ["일본어 이름", data.sake.ja], ["한국어 이름", data.sake.ko], ["가나", data.sake.kana],
        ["양조장", choiceLabel(data.brewery)], ["브랜드", choiceLabel(data.brand)],
        ...textFields.map(([key,title]) => [title,data[key]]),
        ...rangeFields.map(([key,title]) => {
            const range = data[key];
            return [title, range ? range.max == null ? String(range.min) : `${range.min} ~ ${range.max}` : null];
        }),
        ["용량", data.capacityMl == null ? null : `${data.capacityMl} ml`], ["출처 URL", data.sourceUrl], ["추가 설명", data.notes],
        ["이미지 권리 근거", data.imageRights], ["이미지 이용 동의", data.imageConsent ? "동의함" : null],
    ];
    return <div className="mt-4 space-y-4">
        <dl className="grid gap-3 sm:grid-cols-2">
            {values.filter(([,value]) => value != null).map(([label,value]) =>
                <div className="min-w-0 rounded bg-stone-50 p-3" key={label}>
                    <dt className="text-sm text-stone-600">{label}</dt><dd className="mt-1 whitespace-pre-wrap break-words text-sm">{value}</dd>
                </div>)}
        </dl>
        {revision.attachmentId && revision.previewUrl ? (showPreview ?
            <PrivatePreview key={revision.attachmentId} attachment={revision.attachmentId} submission={id} /> :
            <button className="ui-button-secondary" onClick={() => setShowPreview(true)}>이전 첨부 미리보기</button>) :
            revision.attachmentId && <p className="text-sm text-stone-600">이 리비전에는 이미지가 첨부되었습니다.</p>}
    </div>;
}
const revisionLabels: Record<string,string> = { INITIAL: "최초 제보", MEMBER_REVISION: "사용자 보완", ADMIN_REVISION: "관리자 검토 수정" };

export default function SubmissionDetail({ id }: { id: string }) {
    const guard = useSessionGuard();
    const { member } = useAuth();
    const cache = useQueryClient();
    const query = useQuery({ queryKey: ["my-submission", member?.memberNo, id],
        queryFn: ({ signal }) => getSubmission(id, signal), retry: false,
        refetchInterval: result => result.state.data?.submission.approvalInProgress ? 10000 : false });
    const [editing, setEditing] = useState(false);
    const [confirm, setConfirm] = useState(false);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState("");
    const cancelling = useRef(false);
    function update() {
        void cache.invalidateQueries({ queryKey: ["my-submissions", member?.memberNo] });
        return query.refetch();
    }
    if (query.isPending) return <p role="status" className="ui-state">제보를 불러오는 중…</p>;
    if (query.isError) return <div role="alert" className="ui-state">{submissionError(query.error)}{" "}
        <button className="underline" onClick={() => void query.refetch()}>다시 불러오기</button></div>;
    const { submission, revisions, events } = query.data;
    const current = revisions.find(revision => revision.number === submission.currentRevision);
    const open = submission.status === "PENDING" || submission.status === "NEEDS_REVISION";
    const revisable = submission.status === "NEEDS_REVISION" && !submission.approvalInProgress;
    const cancellable = open && !submission.approvalInProgress;

    async function resubmit(data: SubmissionInput) {
        const valid = guard();
        setNotice("");
        try {
            await reviseSubmission(id,submission.version,data);
            if (!valid()) return;
            setEditing(false);
            setNotice("보완 제보를 제출했습니다.");
            await update();
        } catch (error) {
            if (!valid()) return;
            if (!conflict(error)) throw error;
            setNotice("제보 상태가 바뀌었습니다. 최신 내용을 확인해 주세요.");
            await update();
        }
    }

    async function cancel(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (cancelling.current) return;
        cancelling.current = true;
        setBusy(true);
        const valid = guard();
        setNotice("");
        try {
            const reason = String(new FormData(event.currentTarget).get("reason") ?? "").trim();
            await cancelSubmission(id,submission.version,reason || null);
            if (!valid()) return;
            setConfirm(false);
            setNotice("제보를 취소했습니다.");
            await update();
        } catch (error) {
            if (!valid()) return;
            if (conflict(error)) {
                setConfirm(false);
                setNotice("제보 상태가 바뀌었습니다. 최신 내용을 확인해 주세요.");
                await update();
            } else setNotice(submissionError(error));
        } finally {
            cancelling.current = false;
            if (valid()) setBusy(false);
        }
    }

    return <div className="space-y-5">
        <section className="ui-panel space-y-4 p-5 sm:p-7" aria-labelledby="submission-current">
            <p className="text-sm font-medium text-stone-600">{statusLabels[submission.status]} · 리비전 {submission.currentRevision}</p>
            <h2 id="submission-current" className="break-words text-xl font-semibold">{submission.nameKo || submission.nameJa || "현재 제보"}</h2>
            {submission.approvalInProgress && <p role="status" className="rounded bg-amber-50 p-3 text-sm text-stone-800">이미지 승인 작업이 진행 중입니다. 작업 중에는 보완하거나 취소할 수 없습니다.</p>}
            {submission.status === "APPROVED" && submission.sakeNo &&
                <Link className="ui-button-secondary" href={`/sake/${submission.sakeNo}`}>공개 사케 보기</Link>}
            {current && <RevisionContent revision={current} id={id} />}
            <div className="flex flex-wrap gap-2">
                {revisable && <button className="ui-button" onClick={() => { setEditing(!editing); setConfirm(false); }}>{editing ? "보완 입력 닫기" : "보완하기"}</button>}
                {cancellable && !confirm && <button className="ui-button-secondary" onClick={() => { setConfirm(true); setEditing(false); }}>제보 취소</button>}
                {open && <button className="ui-button-secondary" onClick={() => void update()}>상태 새로고침</button>}
            </div>
            {notice && <p role="status" className="rounded bg-stone-100 p-3 text-sm">{notice}</p>}
            {confirm && cancellable && <form onSubmit={cancel} className="space-y-3 rounded border border-stone-200 p-4">
                <p className="text-sm">제보를 취소한 뒤에는 다시 열 수 없습니다.</p>
                <label className="grid gap-2 text-sm">취소 사유 (선택)<input className="ui-input" name="reason" maxLength={500} disabled={busy} /></label>
                <div className="flex flex-wrap gap-2">
                    <button className="ui-button" disabled={busy} type="submit">제보 취소 확인</button>
                    <button className="ui-button-secondary" disabled={busy} type="button" onClick={() => setConfirm(false)}>계속 보관</button>
                </div>
            </form>}
        </section>
        {editing && revisable && current && <SubmissionForm key={current.number} initial={current} submissionId={id} onSave={resubmit} />}
        {revisions.some(revision => revision.number !== current?.number) && <section className="ui-panel space-y-3 p-5 sm:p-7">
            <h2 className="text-xl font-semibold">이전 리비전</h2>
            {revisions.filter(revision => revision.number !== current?.number).map(revision =>
                <details className="border-t border-stone-200 pt-3" key={revision.number}>
                    <summary className="cursor-pointer text-sm">리비전 {revision.number} · {revisionLabels[revision.kind] ?? "제보"} · {submissionDate(revision.createdAt)}</summary>
                    <RevisionContent revision={revision} id={id} historical />
                </details>)}
        </section>}
        <section className="ui-panel p-5 sm:p-7">
            <h2 className="text-xl font-semibold">처리 내역</h2>
            <ol className="mt-3 divide-y divide-stone-200">{events.map((event,index) => <li className="py-3" key={index}>
                <p className="text-sm font-medium">{statusLabels[event.next]} · {event.actorRole === "ADMIN" ? "관리자" : "사용자"}</p>
                <p className="my-1 whitespace-pre-wrap break-words text-sm">{event.reason}</p>
                <p className="text-xs text-stone-600">{submissionDate(event.createdAt)}</p>
            </li>)}</ol>
        </section>
    </div>;
}
