import axios from "axios";
import api from "./axios";
import { errorMessage } from "./auth";

export const submissionsPath = "/account/submissions";
const root = "/user/sake-submissions";

export type Names = { ja: string; ko: string; kana?: string | null };
export type Choice = { existingNo: number; proposed?: null } | { existingNo?: null; proposed: Names };
export type Range = { min: number; max?: number | null };
export type RangeKey = "sakeMeter" | "acidity" | "polishingPercent" | "alcoholPercent";
export type SubmissionInput = {
    sake: Names; brewery: Choice; brand?: Choice | null;
    designation?: string | null; sakeMeter?: Range | null; acidity?: Range | null;
    polishingPercent?: Range | null; alcoholPercent?: Range | null;
    riceJa?: string | null; riceKo?: string | null; yeastJa?: string | null; yeastKo?: string | null;
    capacityMl?: number | null; variant?: string | null; sourceUrl?: string | null; notes?: string | null;
    attachmentId?: string | null; imageRights?: string | null; imageConsent?: boolean | null;
};
export type Status = "PENDING" | "NEEDS_REVISION" | "CANCELLED" | "REJECTED" | "APPROVED";
type Receipt = { id: string; status: Status; version: number; currentRevision: number };
export type Summary = Receipt & {
    createdAt: string; updatedAt: string; approvalInProgress: boolean;
    sakeNo: number | null; nameJa: string | null; nameKo: string | null;
};
export type Revision = {
    number: number; kind: string; data: SubmissionInput; attachmentId: string | null;
    previewUrl: string | null; createdAt: string;
};
type SubmissionEvent = { revision: number; actorRole: string; next: Status; reason: string; createdAt: string };
export type Detail = { submission: Summary; revisions: Revision[]; events: SubmissionEvent[] };
export type Option = Names & { no: number };
type Page<T> = { items: T[]; page: number; hasMore: boolean };
export type Upload = { attachmentId: string; expiresAt: string };

export async function listSubmissions(page: number, signal: AbortSignal) {
    return (await api.get<Page<Summary>>(root, { params: { page }, signal })).data;
}
export async function getSubmission(id: string, signal: AbortSignal) {
    return (await api.get<Detail>(`${root}/${encodeURIComponent(id)}`, { signal })).data;
}
export async function searchCatalog(kind: "breweries" | "brands", q: string, page: number,
                                    breweryNo: number | null, signal: AbortSignal) {
    return (await api.get<Page<Option>>(`/user/catalog/${kind}`, { params: { q, page, breweryNo }, signal })).data;
}
export async function submitSake(data: SubmissionInput, key: string) {
    return (await api.post<Receipt>(root, data, { headers: { "Idempotency-Key": key } })).data;
}
export async function reviseSubmission(id: string, expectedVersion: number, data: SubmissionInput) {
    return (await api.post<Receipt>(`${root}/${encodeURIComponent(id)}/revisions`, { expectedVersion, data })).data;
}
export async function cancelSubmission(id: string, expectedVersion: number, reason: string | null) {
    return (await api.post<Receipt>(`${root}/${encodeURIComponent(id)}/cancel`, { expectedVersion, reason })).data;
}
export async function uploadImage(file: File, key: string) {
    const body = new FormData();
    body.append("file", file);
    return (await api.post<Upload>(`${root}/uploads`, body, {
        headers: { "Idempotency-Key": key }, timeout: 90000,
    })).data;
}
export async function previewImage(attachment: string, submission: string | undefined, signal: AbortSignal) {
    const asset = encodeURIComponent(attachment);
    const path = submission ? `${root}/${encodeURIComponent(submission)}/attachments/${asset}` : `${root}/uploads/${asset}/preview`;
    const { data } = await api.get<Blob>(path, { responseType: "blob", signal });
    if (!["image/jpeg", "image/png"].includes(data.type)) throw new Error("이미지 응답을 확인할 수 없습니다.");
    return data;
}
export function conflict(error: unknown) {
    return axios.isAxiosError(error) && error.response?.status === 409;
}
export function submissionError(error: unknown) {
    if (axios.isAxiosError(error) && error.response?.status === 404) return "제보를 찾을 수 없거나 열람 권한이 없습니다.";
    return errorMessage(error);
}
