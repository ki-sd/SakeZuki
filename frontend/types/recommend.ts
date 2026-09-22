// 두 추천 방향의 요청을 분리해 음식 문자열과 실제 제품 번호를 혼동하지 않게 한다.
export interface SakeRecommendRequest {
    food:string;
}

// AI가 고르는 대상은 no이고, 제품명·수치·이미지는 백엔드의 DB 조회 결과를 받는다.
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

// 자유 입력 사케명 대신 선택된 제품의 no만 서버로 보낸다.
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