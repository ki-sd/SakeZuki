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
    const response=await api.post<SakeRecommendResponse>("/recommend/sake", request,
        {
            timeout:60000
        }
    );

    return response.data;
};

export const recommendFood=async (request:FoodRecommendRequest):Promise<FoodRecommendResponse> => {
    const response=await api.post<FoodRecommendResponse>("/recommend/food", request,
        {
            timeout:60000
        }
    );

    return response.data;
};