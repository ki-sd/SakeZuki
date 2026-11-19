import axios from "axios";
import api from "./axios";
import { authStore } from "./auth";
import { FoodRecommendRequest, FoodRecommendResponse, SakeRecommendRequest, SakeRecommendResponse } from "@/types/recommend";

// 결과를 받지 못한 요청 키는 탭 새로고침 뒤에도 재사용한다. 원문 입력·토큰은 저장하지 않는다.
const pending = new Map<string, Promise<unknown>>();
function paid<T>(kind: string, input: FoodRecommendRequest | SakeRecommendRequest): Promise<T> {
    const member = authStore.snapshot().member?.memberNo;
    const identity = member + ":" + kind + ":" + JSON.stringify(input);
    const existing = pending.get(identity);
    if (existing) return existing as Promise<T>;
    const operation = (async () => {
        // 비동기 해시 계산 전에 in-flight 요청을 등록해 빠른 캐시 응답에서도 더블클릭을 합친다.
        const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(JSON.stringify(input)));
        const hash = Array.from(new Uint8Array(bytes), value => value.toString(16).padStart(2, "0")).join("");
        const slot = "sakezuki-request:" + member + ":" + kind + ":" + hash;
        const key = sessionStorage.getItem(slot) ?? crypto.randomUUID();
        sessionStorage.setItem(slot, key);
        try {
            const response = await api.post<T>("/recommend/" + kind, input, { timeout: 120000, headers: { "Idempotency-Key": key } });
            sessionStorage.removeItem(slot);
            return response.data;
        } catch (error) {
            // 응답 유실·처리 중·서버 오류는 확정 여부가 불명확해 같은 키로 확인한다.
            if (axios.isAxiosError(error) && error.response && [400, 401, 402, 403, 410].includes(error.response.status))
                sessionStorage.removeItem(slot);
            if (axios.isAxiosError(error) && error.response?.status === 409
                && typeof error.response.data === "string" && error.response.data.startsWith("실패한 요청"))
                sessionStorage.removeItem(slot);
            throw error;
        }
    })().finally(() => pending.delete(identity));
    pending.set(identity, operation);
    return operation;
}
export const recommendSake = (request: SakeRecommendRequest) => paid<SakeRecommendResponse>("sake", request);
export const recommendFood = (request: FoodRecommendRequest) => paid<FoodRecommendResponse>("food", request);
