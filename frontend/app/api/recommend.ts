import api from "./axios";
import {
    FoodRecommendRequest,
    FoodRecommendResponse,
    SakeRecommendRequest,
    SakeRecommendResponse
} from "@/types/recommend";

export const recommendSake=async (
    request:SakeRecommendRequest
):Promise<SakeRecommendResponse> => {
    // 최초 추천은 DB 후보 검색과 Gemini 재선정이 이어질 수 있어 공통 10초보다 긴 제한 시간을 준다.
    // 응답의 제품 정보는 백엔드가 실제 DB 조회 결과로 채운다.
    const response=await api.post<SakeRecommendResponse>("/recommend/sake", request,
        {
            timeout:60000
        }
    );

    return response.data;
};

export const recommendFood=async (request:FoodRecommendRequest):Promise<FoodRecommendResponse> => {
    // 화면의 제품명 대신 sakeNo를 전달해 DB의 정확한 제품과 추천 결과를 연결한다.
    const response=await api.post<FoodRecommendResponse>("/recommend/food", request,
        {
            timeout:60000
        }
    );

    return response.data;
};