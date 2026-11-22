import { hasPendingRecommendation, recommendFood, recommendSake } from "./recommend";
import { matchHistory } from "./account";
import type { History } from "./account";
import type { RecommendationInput, Snapshot } from "@/types/pairing";

type Decision = { type: "duplicate"; previous: History; condition: RecommendationInput } | { type: "completed"; result: Snapshot };
export async function requestNewRecommendation(condition: RecommendationInput): Promise<Snapshot> {
    return condition.kind === "SAKE" ? recommendSake({ food: condition.food }) : recommendFood({ sakeNo: condition.sakeNo });
}
export async function prepareRecommendation(condition: RecommendationInput): Promise<Decision> {
    const request = condition.kind === "SAKE" ? { food: condition.food } : { sakeNo: condition.sakeNo };
    // 응답이 유실된 요청은 기존 키로 먼저 확인한다. 이미 확정된 결과를 새 주문으로 바꾸지 않는다.
    if (!await hasPendingRecommendation(condition.kind.toLowerCase(), request)) {
        const previous = await matchHistory(condition);
        if (previous) return { type: "duplicate", previous, condition };
    }
    // 중복 확인 실패는 그대로 전달해 차감을 중단한다. 확인 응답이 없었다고 새 주문을 가정하지 않는다.
    return { type: "completed", result: await requestNewRecommendation(condition) };
}
