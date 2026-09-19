import api from "./axios";
import type {SakeListItem, SakeListResponse} from "@/app/types/sake";

// 사케 목록 조회 API
export const getSakeList=async (
    page:number=1,
    fd?:string,
    sakeType?:string
):Promise<SakeListResponse>=>{
    // params 객체는 axios가 URl의 Query Parameter로 변환
    const response=await api.get<SakeListResponse>("/sake/list",{
        params:{
            page,
            fd,
            sakeType
        }
    });
    return response.data;
}