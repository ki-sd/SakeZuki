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