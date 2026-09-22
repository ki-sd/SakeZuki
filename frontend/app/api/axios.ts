import axios from "axios";

// 목록·상세·추천 API가 같은 서버 주소와 기본 제한 시간을 쓰도록 인스턴스를 공유한다.
// 상대 경로 /api는 개발·배포 환경의 프록시를 통하게 해 화면과 API 주소를 분리한다.
const api=axios.create({
    baseURL:"/api",
    timeout:10000
});

export default api;