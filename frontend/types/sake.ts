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

export interface SakeListResponse{
    count:number;
    curpage:number;
    totalpage:number;
    startPage:number;
    endPage:number;
    list:SakeListItem[];
}

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