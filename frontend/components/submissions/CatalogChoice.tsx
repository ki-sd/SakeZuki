"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { searchCatalog, submissionError, type Choice, type Names, type Option } from "@/app/api/submissions";
import { useAuth } from "@/components/auth/AuthProvider";

type Mode = "existing" | "proposed" | "none";
export const blankNames: Names = { ja: "", ko: "", kana: "" };

export function NameInputs({ title, value, onChange }: { title: string; value: Names; onChange: (value: Names) => void }) {
    return <div className="grid gap-4 sm:grid-cols-2">
        {([{ key: "ja", label: "일본어 이름", max: 500 }, { key: "ko", label: "한국어 이름", max: 1000 },
            { key: "kana", label: "가나", max: 500 }] as const).map(({ key, label, max }) =>
            <label className="grid gap-1.5 text-sm" key={key}>{title} {label}{key !== "kana" && " (필수)"}
                <input className="ui-input" required={key !== "kana"} maxLength={max} value={value[key] ?? ""}
                    onChange={event => onChange({ ...value, [key]: event.target.value })} />
            </label>)}
    </div>;
}
export default function CatalogChoice({ kind, breweryNo, value, onChange }: {
    kind: "breweries" | "brands"; breweryNo: number | null; value: Choice | null; onChange: (value: Choice | null) => void;
}) {
    const brand = kind === "brands";
    const title = brand ? "브랜드" : "양조장";
    const canSearch = !brand || breweryNo !== null;
    const [mode, setMode] = useState<Mode>(value?.proposed ? "proposed" : brand && !value ? "none" : "existing");
    const [term, setTerm] = useState("");
    const [query, setQuery] = useState("");
    const [page, setPage] = useState(0);
    const [option, setOption] = useState<Option | null>(null);
    const { member } = useAuth();
    const lookup = useQuery({ queryKey: ["submission-catalog", member?.memberNo, kind, breweryNo, query, page],
        queryFn: ({ signal }) => searchCatalog(kind, query, page, breweryNo, signal),
        enabled: !!member && mode === "existing" && canSearch, retry: false });
    function change(next: Mode) {
        setMode(next);
        setOption(null);
        onChange(next === "proposed" ? { proposed: blankNames } : null);
    }
    return <div className="space-y-4">
        <fieldset className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
            <legend className="mb-2 font-medium">{title} 등록 방식</legend>
            {brand && <label><input type="radio" name={kind} checked={mode === "none"} onChange={() => change("none")} /> 없음</label>}
            {canSearch && <label><input type="radio" name={kind} checked={mode === "existing"} onChange={() => change("existing")} /> 기존 {title} 선택</label>}
            <label><input type="radio" name={kind} checked={mode === "proposed"} onChange={() => change("proposed")} /> 새 {title} 제안</label>
        </fieldset>
        {mode === "proposed" && <NameInputs title={title} value={value?.proposed ?? blankNames}
            onChange={proposed => onChange({ proposed })} />}
        {mode === "existing" && canSearch && <div className="space-y-3">
            <div className="flex flex-wrap items-end gap-2">
                <label className="grid min-w-0 grow gap-1.5 text-sm">{title} 검색어
                    <input className="ui-input" maxLength={100} value={term} onChange={event => setTerm(event.target.value)}
                        onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); setQuery(term.trim()); setPage(0); } }} />
                </label>
                <button type="button" className="ui-button-secondary" onClick={() => { setQuery(term.trim()); setPage(0); }}>{title} 검색</button>
            </div>
            <p className="text-sm text-stone-600" role="status">{value?.existingNo ?
                `선택됨 · ${option?.ko || option?.ja || `등록 번호 ${value.existingNo}`}` : `${title} 한 곳을 선택해 주세요.`}</p>
            {lookup.isPending && <p role="status" className="text-sm">검색 결과를 불러오는 중…</p>}
            {lookup.isError && <div role="alert" className="text-sm">{submissionError(lookup.error)}{" "}
                <button type="button" className="underline" onClick={() => void lookup.refetch()}>다시 검색</button></div>}
            {lookup.data?.items.length === 0 && <p className="text-sm text-stone-600">검색 결과가 없습니다.</p>}
            {!!lookup.data?.items.length && <ul className="max-h-48 divide-y divide-stone-200 overflow-y-auto rounded border border-stone-200">
                {lookup.data.items.map(item => <li key={item.no}>
                    <button type="button" aria-pressed={value?.existingNo === item.no}
                        className="block w-full break-words px-3 py-2 text-left text-sm hover:bg-stone-50 aria-pressed:bg-stone-100"
                        onClick={() => { setOption(item); onChange({ existingNo: item.no }); }}>
                        {item.ko && <span className="mr-2 font-medium">{item.ko}</span>}{item.ja}
                    </button>
                </li>)}
            </ul>}
            {(page > 0 || lookup.data?.hasMore) && <nav aria-label={`${title} 검색 페이지`} className="flex items-center gap-4 text-sm">
                <button type="button" className="ui-button-secondary disabled:opacity-40" disabled={!page || lookup.isFetching} onClick={() => setPage(page - 1)}>이전</button>
                <span>{page + 1}페이지</span>
                <button type="button" className="ui-button-secondary disabled:opacity-40" disabled={!lookup.data?.hasMore || lookup.isFetching} onClick={() => setPage(page + 1)}>다음</button>
            </nav>}
        </div>}
    </div>;
}
