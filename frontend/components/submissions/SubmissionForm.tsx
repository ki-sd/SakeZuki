"use client";

import { useSessionGuard } from "@/components/auth/AuthProvider";
import { useRef, useState, type FormEvent } from "react";
import { submissionError, uploadImage, type Choice, type Names, type Range, type RangeKey, type Revision,
    type SubmissionInput, type Upload } from "@/app/api/submissions";
import { parseSubmissionDate } from "@/commons/submission-date";
import CatalogChoice, { blankNames, NameInputs } from "./CatalogChoice";
import PrivatePreview from "./PrivatePreview";

export const textFields = [
    ["designation", "특정명칭", 100], ["riceJa", "주미 일본어", 500], ["riceKo", "주미 한국어", 500],
    ["yeastJa", "효모 일본어", 500], ["yeastKo", "효모 한국어", 500], ["variant", "상품 변형", 500],
] as const;
export const rangeFields: readonly [RangeKey, string, number, number][] = [
    ["sakeMeter", "니혼슈도", -1000, 1000], ["acidity", "산도", 0, 100],
    ["polishingPercent", "정미율 (%)", 0, 100], ["alcoholPercent", "알코올 도수 (%)", 0, 100],
];

const allowedTypes = ["image/jpeg", "image/png"];
function normalizeNames(names: Names): Names {
    return { ja: names.ja.trim(), ko: names.ko.trim(), kana: names.kana?.trim() || null };
}
function normalizeChoice(value: Choice): Choice {
    return value.proposed ? { proposed: normalizeNames(value.proposed) } : value;
}
function optional(form: FormData, key: string) {
    const value = String(form.get(key) ?? "");
    return value.trim() ? value : null;
}
function readRange(form: FormData, key: RangeKey, title: string): Range | null {
    const lower = optional(form, `${key}Min`);
    const upper = optional(form, `${key}Max`);
    if (lower === null && upper === null) return null;
    if (lower === null || (upper !== null && Number(upper) < Number(lower)))
        throw new Error(`${title}의 최솟값과 최댓값을 확인해 주세요.`);
    return { min: Number(lower), max: upper === null ? null : Number(upper) };
}

export default function SubmissionForm({ initial, submissionId, onSave }: {
    initial?: Revision; submissionId?: string; onSave: (data: SubmissionInput) => Promise<void>;
}) {
    const guard = useSessionGuard();
    const [sake, setSake] = useState<Names>(initial?.data.sake ?? blankNames);
    const [brewery, setBrewery] = useState<Choice | null>(initial?.data.brewery ?? null);
    const [brand, setBrand] = useState<Choice | null>(initial?.data.brand ?? null);
    const [attachment, setAttachment] = useState<string | null>(initial?.attachmentId ?? null);
    const [upload, setUpload] = useState<Upload | null>(null);
    const [file, setFile] = useState<File | null>(null);
    const [imageRights, setImageRights] = useState(initial?.data.imageRights ?? "");
    const [imageConsent, setImageConsent] = useState(initial?.data.imageConsent ?? false);
    const [busy, setBusy] = useState<"upload" | "save" | null>(null);
    const [error, setError] = useState("");
    const pending = useRef(false);
    const uploadKey = useRef<string | null>(null);
    const details = useRef<HTMLDetailsElement>(null);
    const fileInput = useRef<HTMLInputElement>(null);

    function replaceFile(next: File | null) {
        setError("");
        if (!next) return;
        if (!allowedTypes.includes(next.type) || !next.size || next.size > 10 * 1024 * 1024) {
            setError("10 MiB 이하의 비어 있지 않은 JPEG 또는 PNG 이미지를 선택해 주세요.");
            if (fileInput.current) fileInput.current.value = "";
            setFile(null);
            return;
        }
        setFile(next);
        uploadKey.current = crypto.randomUUID();
    }

    async function uploadSelected() {
        if (!file || !uploadKey.current || pending.current) return;
        const valid = guard();
        pending.current = true;
        setBusy("upload");
        setError("");
        try {
            const result = await uploadImage(file, uploadKey.current);
            if (!valid()) return;
            setAttachment(result.attachmentId);
            setUpload(result);
            setImageRights("");
            setImageConsent(false);
            setFile(null);
            if (fileInput.current) fileInput.current.value = "";
        } catch (failure) {
            if (valid()) setError(submissionError(failure));
        } finally {
            pending.current = false;
            if (valid()) setBusy(null);
        }
    }

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending.current) return;
        setError("");
        const values = new FormData(event.currentTarget);
        if (!brewery) return setError("양조장을 선택하거나 새 양조장을 제안해 주세요.");
        if (file) return setError("선택한 파일을 먼저 업로드하거나 파일 선택을 취소해 주세요.");
        if (attachment && (!imageConsent || !imageRights.trim())) return setError("이미지 이용 동의와 권리 근거를 입력해 주세요.");
        if (upload) {
            const expiresAt = parseSubmissionDate(upload.expiresAt);
            if (expiresAt === null || expiresAt <= Date.now())
                return setError("첨부 보관 기간을 확인할 수 없거나 기간이 끝났습니다. 이미지를 다시 업로드해 주세요.");
        }
        const sourceUrl = optional(values, "sourceUrl")?.trim() ?? null;
        if (sourceUrl) {
            try {
                const url = new URL(sourceUrl);
                if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) throw new Error();
            } catch { return setError("출처 URL은 http 또는 https 주소로 입력해 주세요."); }
        }
        const ranges: Partial<Record<RangeKey,Range | null>> = {};
        for (const [key,title] of rangeFields) {
            try {
                ranges[key] = readRange(values,key,title);
            } catch (failure) {
                if (details.current) details.current.open = true;
                const field = event.currentTarget.elements.namedItem(`${key}${optional(values,`${key}Min`) ? "Max" : "Min"}`);
                if (field instanceof HTMLInputElement) field.focus();
                setError(failure instanceof Error ? failure.message : "수치 범위를 확인해 주세요.");
                return;
            }
        }
        const valid = guard();
        pending.current = true;
        setBusy("save");
        try {
            const text = Object.fromEntries(textFields.map(([key]) => [key, optional(values,key)]));
            const capacity = optional(values,"capacityMl");
            await onSave({ sake: normalizeNames(sake), brewery: normalizeChoice(brewery),
                brand: brand ? normalizeChoice(brand) : null, ...text, ...ranges, sourceUrl, notes: optional(values,"notes"),
                capacityMl: capacity === null ? null : Number(capacity), attachmentId: attachment,
                imageRights: attachment ? imageRights : null, imageConsent: attachment ? imageConsent : null });
        } catch (failure) {
            if (valid()) setError(failure instanceof Error && failure.name === "Error" ? failure.message : submissionError(failure));
        } finally {
            pending.current = false;
            if (valid()) setBusy(null);
        }
    }

    function removeAttachment() {
        setAttachment(null);
        setUpload(null);
        setImageRights("");
        setImageConsent(false);
    }

    const breweryNo = brewery?.existingNo ?? null;
    return <form onSubmit={save} className="mt-4">
        <fieldset disabled={busy !== null} className="min-w-0 space-y-5">
            <section className="ui-panel space-y-5 p-5 sm:p-7" aria-labelledby="sake-form-title">
                <h2 id="sake-form-title" className="text-xl font-semibold">사케 이름</h2>
                <NameInputs title="사케" value={sake} onChange={setSake} />
            </section>
            <section className="ui-panel space-y-5 p-5 sm:p-7" aria-labelledby="brewery-form-title">
                <h2 id="brewery-form-title" className="text-xl font-semibold">양조장과 브랜드</h2>
                <CatalogChoice kind="breweries" breweryNo={null} value={brewery} onChange={next => {
                    if ((next?.existingNo ?? null) !== breweryNo && brand?.existingNo) setBrand(null);
                    setBrewery(next);
                }} />
                <div className="border-t border-stone-200 pt-5">
                    <CatalogChoice key={breweryNo ?? "new-brewery"} kind="brands" breweryNo={breweryNo} value={brand} onChange={setBrand} />
                </div>
            </section>
            <details ref={details} className="ui-panel p-5 sm:p-7" onInvalid={event => { event.currentTarget.open = true; }}>
                <summary className="cursor-pointer text-xl font-semibold">상품 세부 정보 (선택)</summary>
                <div className="mt-5 grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2">
                    {textFields.map(([key,title,max]) => <label className="grid min-w-0 gap-1.5 text-sm" key={key}>{title}
                        <input className="ui-input w-full" name={key} maxLength={max} defaultValue={initial?.data[key] ?? ""} /></label>)}
                    <label className="grid min-w-0 gap-1.5 text-sm">용량 (ml)
                        <input className="ui-input w-full" type="number" name="capacityMl" min={1} max={100000} step={1}
                            defaultValue={initial?.data.capacityMl ?? ""} /></label>
                    <label className="grid min-w-0 gap-1.5 text-sm">출처 URL
                        <input className="ui-input w-full" inputMode="url" name="sourceUrl" maxLength={1000} defaultValue={initial?.data.sourceUrl ?? ""} /></label>
                    {rangeFields.map(([key,title,min,max]) => <fieldset key={key} className="min-w-0">
                        <legend className="mb-2 text-sm">{title}</legend>
                        <div className="grid grid-cols-1 gap-2 min-[380px]:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                            {(["Min", "Max"] as const).map((end,index) => <label className="grid min-w-0 gap-1 text-sm" key={end}>
                                {index ? "최댓값" : "값 또는 최솟값"}
                                <input className="ui-input w-full" type="number" step="0.001" min={min} max={max}
                                    name={`${key}${end}`} aria-label={`${title} ${index ? "최댓값" : "값 또는 최솟값"}`}
                                    defaultValue={initial?.data[key]?.[index ? "max" : "min"] ?? ""} /></label>)}
                        </div>
                    </fieldset>)}
                    <label className="grid min-w-0 gap-1.5 text-sm sm:col-span-2">추가 설명
                        <textarea className="ui-input w-full min-h-24 py-2" name="notes" maxLength={2000} defaultValue={initial?.data.notes ?? ""} /></label>
                </div>
            </details>
            <section className="ui-panel space-y-4 p-5 sm:p-7" aria-labelledby="image-form-title">
                <h2 id="image-form-title" className="text-xl font-semibold">상품 이미지 (선택)</h2>
                <p className="text-sm text-stone-600">JPEG 또는 PNG, 최대 10 MiB. 첨부는 검토 중 비공개입니다.</p>
                <label className="grid min-w-0 grid-cols-1 gap-2 text-sm">이미지 파일
                    <input ref={fileInput} className="w-full min-w-0 max-w-full text-sm file:mr-3 file:rounded file:border file:border-stone-300 file:bg-white file:px-3 file:py-2"
                        type="file" accept="image/jpeg,image/png" onChange={e => replaceFile(e.target.files?.[0] ?? null)} /></label>
                {file && <div className="flex flex-wrap gap-2">
                    <button className="ui-button-secondary" type="button" onClick={() => void uploadSelected()}>이미지 업로드</button>
                    <button className="ui-button-secondary" type="button" onClick={() => { setFile(null); if (fileInput.current) fileInput.current.value = ""; }}>파일 선택 취소</button>
                </div>}
                {busy === "upload" && <p role="status" className="text-sm">이미지를 업로드하고 있습니다…</p>}
                {attachment && <>
                    <PrivatePreview key={attachment} attachment={attachment} submission={upload ? undefined : submissionId} />
                    <button className="ui-button-secondary" type="button" onClick={removeAttachment}>첨부 제거</button>
                    <label className="grid min-w-0 gap-1.5 text-sm">이미지 권리 근거 (필수)
                        <textarea className="ui-input min-h-20 py-2" required maxLength={1000} value={imageRights}
                            onChange={e => setImageRights(e.target.value)} /></label>
                    <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={imageConsent}
                        onChange={e => setImageConsent(e.target.checked)} required />
                        <span>이미지 이용 근거를 확인했으며 검토 및 승인 후 공개에 동의합니다.</span>
                    </label>
                </>}
            </section>
            {error && <p role="alert" className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-800">{error}</p>}
            <button className="ui-button" type="submit">{busy === "save" ? "제출 중…" : initial ? "보완 제출" : "제보 제출"}</button>
        </fieldset>
    </form>;
}
