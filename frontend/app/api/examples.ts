import api from "./axios";
import type { PublicExample } from "@/types/pairing";
// 고정된 공개 목록만 조회한다. 유료 추천 POST나 개인 기록 API를 재사용하지 않는다.
export const getExamples = async () => (await api.get<PublicExample[]>("/examples/recommendations")).data;
