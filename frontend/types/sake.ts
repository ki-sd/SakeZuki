// 목록 API의 사케 한 건. | null은 수집·번역 데이터가 비어 있을 수 있음을 타입에 드러내
// 카드에서 한국어 이름이나 이미지의 대체값을 준비하게 한다.
export interface SakeListItem{
    no:number;
    nameJa:string;
    nameKana:string | null;
    nameKo:string | null;
    sakeType:string | null;
    imageUrl:string | null;
    brandNameKo:string | null;
    breweryNameKo:string | null;
    prefecture:string | null;
}

// 목록뿐 아니라 페이지 경계와 전체 건수를 함께 받으므로 페이지 버튼도 서버 결과를 기준으로 만든다.
export interface SakeListResponse{
    count:number;
    curpage:number;
    totalpage:number;
    startPage:number;
    endPage:number;
    list:SakeListItem[];
}

// 자동완성 선택에 필요한 최소 정보이며 no가 이후 추천 요청의 식별자가 된다.
export interface SakeSearchItem{
    no:number;
    nameJa:string;
    nameKo:string | null;
    imageUrl:string | null;
}

export interface Brand{
    nameJa:string;
    nameKo:string | null;
    nameKana:string | null;
}

// 상세 응답의 양조장 정보. 위치·연락처 등 nullable 필드는 원본에 없는 정보일 수 있다.
export interface Brewery{
    nameJa:string;
    nameKana:string | null;
    nameKo:string | null;
    corporationName:string | null;
    corporationNameKo:string | null;
    foundedYear:string | null;
    ceo:string | null;
    prefecture:string | null;
    address:string | null;
    addressKo:string | null;
    post:string | null;
    phone:string | null;
    fax:string | null;
    email:string | null;
    website:string | null;
    tourAvailable:boolean | null;
    latitude:number | null;
    longitude:number | null;
}

// 상세 API의 결합 결과. 브랜드가 없을 수 있어 Brand | null로, 양조장은 필수 객체로 표현한다.
export interface SakeDetailResponse{
    no:number;
    nameJa:string;
    nameKana:string | null;
    nameKo:string | null;
    sakeType:string | null;
    rice:string | null;
    riceKo:string | null;
    polishingRatio:string | null;
    yeast:string | null;
    yeastKo:string | null;
    sakeMeterValue:string | null;
    acidity:string | null;
    alcoholPercentage:string | null;
    imageUrl:string | null;
    brand:Brand | null;
    brewery:Brewery;
}