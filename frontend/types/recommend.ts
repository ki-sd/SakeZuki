export interface SakeRecommendRequest {
    food:string;
}

export interface RecommendedSake {
    no:number;
    nameKo:string | null;
    nameJa:string;
    imageUrl:string | null;
    sakeType:string | null;
    prefecture:string | null;
    polishingRatio:string | null;
    acidity:string | null;
    alcoholPercentage:string | null;
    breweryNameKo:string | null;
    reason:string;
}

export interface SakeRecommendResponse {
    food:string;
    recommendations:RecommendedSake[];
}

export interface FoodRecommendRequest {
    sakeNo:number;
}

export interface RecommendedFood {
    name:string;
    reason:string;
}

export interface FoodRecommendResponse {
    sakeNo:number;
    recommends:RecommendedFood[];
}