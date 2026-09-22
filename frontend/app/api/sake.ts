import api from "./axios";
import type {SakeDetailResponse, SakeListItem, SakeListResponse, SakeSearchItem} from "@/types/sake";

// 화면은 요청 조건만 넘기고 URL 구성과 응답 형식은 이 모듈에서 맡는다.
// Promise<SakeListResponse>와 get의 제네릭이 서버 JSON을 목록 타입으로 다루게 한다.
// 사케 목록 조회 API
export const getSakeList=async (
    page:number=1,
    fd?:string,
    sakeType?:string
):Promise<SakeListResponse>=>{
    // params의 page·검색어·종류를 Axios가 쿼리 문자열로 변환해 Spring 목록 API에 전달한다.
    // params 객체는 axios가 URl의 Query Parameter로 변환
    const response=await api.get<SakeListResponse>("/sake/list",{
        params:{
            page,
            fd,
            sakeType
        }
    });
    return response.data;
};

// 라우트의 no를 그대로 상세 API 식별자로 사용하므로 목록 카드와 상세 데이터가 연결된다.
// 사케 상세 조회
export const getSakeDetail=async (no:number):Promise<SakeDetailResponse>=>{
    const response=await api.get<SakeDetailResponse>(`/sake/${no}`);
    return response.data;
};

// 추천 화면의 자동완성에서 사용한다. 전체 상세 DTO 대신 선택에 필요한 검색 결과 배열만 받는다.
// 사케 이름 검색
export const searchSake=async (search:string):Promise<SakeSearchItem[]>=>{
    const response=await api.get<SakeSearchItem[]>("/sake/search",{
        params:{
            search
        }
    });

    return response.data;
};