// 필터 값은 DB의 일본어 원문과 일치시켜 보내고, label만 한국어 화면 표시에 사용한다.
// 사케 종류
export const SAKE_TYPES=[
    {label:"전체",value:""},
    {label:"스파클링",value:"スパークリング"},
    {label:"기타",value:"その他"},
    {label:"리큐르",value:"リキュール"},
    {label:"겐슈",value:"原酒"},
    {label:"긴죠",value:"吟醸酒"},
    {label:"다이긴죠",value:"大吟醸酒"},
    {label:"후츠슈",value:"普通酒"},
    {label:"혼긴죠",value:"本吟醸酒"},
    {label:"혼죠조",value:"本醸造酒"},
    {label:"쇼츄",value:"焼酎"},
    {label:"코슈",value:"古酒"},
    {label:"특별 혼죠조",value:"特別本醸造酒"},
    {label:"특별 준마이",value:"特別純米酒"},
    {label:"나마겐슈",value:"生原酒"},
    {label:"준마이 긴죠",value:"純米吟醸酒"},
    {label:"준마이 다이긴죠",value:"純米大吟醸酒"},
    {label:"준마이 나마겐슈",value:"純米生原酒"},
    {label:"준마이",value:"純米酒"},
    {label:"키죠슈",value:"貴醸酒"},
    {label:"비공개",value:"非公開"}
];

// 대응표에 없는 새 종류는 원문을 보여 데이터가 화면에서 사라지지 않게 한다.
// 사케 종류의 화면 표시명 반환
export const getSakeTypeLabel=(sakeType:string | null)=>{
    if(!sakeType) return "종류 정보 없음";

    return SAKE_TYPES.find((type)=>type.value===sakeType)?.label || sakeType;
};

// 지역도 DB 원문을 보존하면서 화면에서만 한국어로 바꾸는 표시용 대응표다.
// 도도부현
export const PREFECTURES=[
    {label:"홋카이도",value:"北海道"},
    {label:"아오모리현",value:"青森県"},
    {label:"이와테현",value:"岩手県"},
    {label:"미야기현",value:"宮城県"},
    {label:"아키타현",value:"秋田県"},
    {label:"야마가타현",value:"山形県"},
    {label:"후쿠시마현",value:"福島県"},
    {label:"이바라키현",value:"茨城県"},
    {label:"도치기현",value:"栃木県"},
    {label:"군마현",value:"群馬県"},
    {label:"사이타마현",value:"埼玉県"},
    {label:"지바현",value:"千葉県"},
    {label:"도쿄도",value:"東京都"},
    {label:"가나가와현",value:"神奈川県"},
    {label:"니가타현",value:"新潟県"},
    {label:"도야마현",value:"富山県"},
    {label:"이시카와현",value:"石川県"},
    {label:"후쿠이현",value:"福井県"},
    {label:"야마나시현",value:"山梨県"},
    {label:"나가노현",value:"長野県"},
    {label:"기후현",value:"岐阜県"},
    {label:"시즈오카현",value:"静岡県"},
    {label:"아이치현",value:"愛知県"},
    {label:"미에현",value:"三重県"},
    {label:"시가현",value:"滋賀県"},
    {label:"교토부",value:"京都府"},
    {label:"오사카부",value:"大阪府"},
    {label:"효고현",value:"兵庫県"},
    {label:"나라현",value:"奈良県"},
    {label:"와카야마현",value:"和歌山県"},
    {label:"돗토리현",value:"鳥取県"},
    {label:"시마네현",value:"島根県"},
    {label:"오카야마현",value:"岡山県"},
    {label:"히로시마현",value:"広島県"},
    {label:"야마구치현",value:"山口県"},
    {label:"도쿠시마현",value:"徳島県"},
    {label:"가가와현",value:"香川県"},
    {label:"에히메현",value:"愛媛県"},
    {label:"고치현",value:"高知県"},
    {label:"후쿠오카현",value:"福岡県"},
    {label:"사가현",value:"佐賀県"},
    {label:"나가사키현",value:"長崎県"},
    {label:"구마모토현",value:"熊本県"},
    {label:"오이타현",value:"大分県"},
    {label:"미야자키현",value:"宮崎県"},
    {label:"가고시마현",value:"鹿児島県"},
    {label:"오키나와현",value:"沖縄県"}
];

// 미매핑 지역은 원문을 fallback으로 사용해 새 수집값도 확인할 수 있게 한다.
// 도도부현의 화면 표시명 반환
export const getPrefectureLabel=(prefecture:string | null)=>{
    if(!prefecture) return "지역 정보 없음";

    return PREFECTURES.find((item)=>item.value===prefecture)?.label || prefecture;
};