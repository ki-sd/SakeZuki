import type { FoodRecommendResponse, SakeRecommendResponse, RecommendedFood } from "./recommend";
import type { SakeDetailResponse } from "./sake";

export type PairingProduct = { no: number; nameKo: string | null; nameJa: string; sakeType: string | null; reason: string; imageUrl?: string | null };
export type PairingView = { kind: "SAKE" | "FOOD"; inputLabel: string; sakes: PairingProduct[]; foods: RecommendedFood[]; selectedSake?: PairingProduct | null };
export type PublicExample = PairingView & { slug: string; title: string; sourceType: "EDITORIAL_SAMPLE"; sourceNote: string };
export type Snapshot = SakeRecommendResponse | (FoodRecommendResponse & { sake?: SakeDetailResponse });
export type RecommendationInput = { kind: "SAKE"; food: string } | { kind: "FOOD"; sakeNo: number };

export function snapshotView(snapshot: Snapshot): PairingView {
    if ("recommendations" in snapshot) return { kind: "SAKE", inputLabel: snapshot.food, sakes: snapshot.recommendations, foods: [] };
    const sake = snapshot.sake;
    return { kind: "FOOD", inputLabel: sake?.nameKo || sake?.nameJa || `사케 #${snapshot.sakeNo}`, sakes: [], foods: snapshot.recommends,
        selectedSake: sake ? { no: snapshot.sakeNo, nameKo: sake.nameKo, nameJa: sake.nameJa, sakeType: sake.sakeType, imageUrl: sake.imageUrl, reason: "" } : null };
}
