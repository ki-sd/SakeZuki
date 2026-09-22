package com.sakezuki.tools.translator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakezuki.tools.model.SakeTranslationData;
import com.sakezuki.tools.model.SakeTranslationResult;
import com.sakezuki.tools.model.TermTranslationData;
import com.sakezuki.tools.model.TermTranslationResult;
import com.sakezuki.tools.repository.TranslationRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 수집값을 정제한 뒤 이름은 제품 단위, 원료미·효모는 중복 원문 단위로 번역한다.
// 결과는 별도 *_ko 열에 저장하므로 화면 표기와 원문을 각각 확인할 수 있다.
public class SakeTranslator {
    private static final int NAME_BATCH_SIZE=50;
    private static final int TERM_BATCH_SIZE=50;

    private final TranslationRepository repository;
    private final GeminiTranslator geminiTranslator;
    private final ObjectMapper objectMapper=new ObjectMapper();

    public SakeTranslator(TranslationRepository repository){
        this.repository=repository;
        this.geminiTranslator=new GeminiTranslator();
    }

    public void translateAll() throws Exception{
        cleanSourceData();
        translateNames();
        translateRice();
        translateYeast();

        System.out.println();
        System.out.println("================================");
        System.out.println("SAKE 전체 번역 완료");
        System.out.println("================================");
    }

    // 웹에서 남은 HTML 표현을 번역 프롬프트에 넣기 전에 처리한다.
    private void cleanSourceData() throws Exception{
        System.out.println();
        System.out.println("SAKE 원본 데이터 정제 시작");

        int riceCount=cleanRice();
        int yeastCount=cleanYeast();

        System.out.println(
                "원본 데이터 정제 완료 | rice="+
                        riceCount+" | yeast="+yeastCount
        );
    }

    private int cleanRice() throws Exception{
        List<String> values=repository.findRiceValues();
        int count=0;

        for(String original:values){
            String cleaned=cleanHtml(original);

            if(!original.equals(cleaned)){
                repository.replaceRiceValue(original,cleaned);
                count++;
            }
        }

        return count;
    }

    private int cleanYeast() throws Exception{
        List<String> values=repository.findYeastValues();
        int count=0;

        for(String original:values){
            String cleaned=cleanHtml(original);

            if(!original.equals(cleaned)){
                repository.replaceYeastValue(original,cleaned);
                count++;
            }
        }

        return count;
    }

    // 한 번에 50건씩 보내고 응답 검증을 통과한 값만 저장한다. 남은 미번역 건을 다시 조회하므로 재시작할 수 있다.
    private void translateNames() throws Exception{
        int total=repository.countUntranslatedSakeNames();
        int complete=0;

        System.out.println();
        System.out.println("SAKE 이름 번역 시작");
        System.out.println("고유 번역 대상: "+total);

        while(true){
            List<SakeTranslationData> sakes=
                    repository.findUntranslatedSakeNames(NAME_BATCH_SIZE);

            if(sakes.isEmpty()) break;

            String prompt=createNamePrompt(sakes);
            String json=geminiTranslator.translateSakeNames(prompt);

            List<SakeTranslationResult> results=objectMapper.readValue(
                    json,
                    new TypeReference<List<SakeTranslationResult>>(){}
            );

            validateNameResults(sakes,results);

            Map<Long,SakeTranslationData> requested=new HashMap<>();

            for(SakeTranslationData sake:sakes){
                requested.put(sake.getNo(),sake);
            }

            for(SakeTranslationResult result:results){
                SakeTranslationData sake=requested.get(result.getNo());

                repository.updateSakeNameKo(
                        sake.getNameJa(),
                        sake.getNameKana(),
                        cleanResult(result.getNameKo())
                );
            }

            complete+=results.size();

            System.out.println(
                    "[SAKE NAME] "+complete+"/"+total+
                            " | 남음="+repository.countUntranslatedSakeNames()
            );
        }

        System.out.println("SAKE 이름 번역 완료");
    }

    // 같은 쌀 표기가 여러 SAKE에 반복되므로 DISTINCT 원문만 요청하고 저장 시 같은 행들에 재사용한다.
    private void translateRice() throws Exception{
        int total=repository.countUntranslatedRice();
        int complete=0;

        System.out.println();
        System.out.println("SAKE 쌀 번역 시작");
        System.out.println("고유 번역 대상: "+total);

        while(true){
            List<String> values=
                    repository.findUntranslatedRice(TERM_BATCH_SIZE);

            if(values.isEmpty()) break;

            List<TermTranslationData> terms=createTerms(values);
            String prompt=createRicePrompt(terms);
            String json=geminiTranslator.translateTerms(prompt);

            List<TermTranslationResult> results=objectMapper.readValue(
                    json,
                    new TypeReference<List<TermTranslationResult>>(){}
            );

            validateTermResults(terms,results);

            Map<Integer,String> originals=createTermMap(terms);

            for(TermTranslationResult result:results){
                repository.updateRiceKo(
                        originals.get(result.getId()),
                        cleanResult(result.getValueKo())
                );
            }

            complete+=results.size();

            System.out.println(
                    "[RICE] "+complete+"/"+total+
                            " | 남음="+repository.countUntranslatedRice()
            );
        }

        System.out.println("SAKE 쌀 번역 완료");
    }

    // 효모도 중복 값을 묶어 번역해 호출량과 행별 표기 차이를 줄인다.
    private void translateYeast() throws Exception{
        int total=repository.countUntranslatedYeast();
        int complete=0;

        System.out.println();
        System.out.println("SAKE 효모 번역 시작");
        System.out.println("고유 번역 대상: "+total);

        while(true){
            List<String> values=
                    repository.findUntranslatedYeast(TERM_BATCH_SIZE);

            if(values.isEmpty()) break;

            List<TermTranslationData> terms=createTerms(values);
            String prompt=createYeastPrompt(terms);
            String json=geminiTranslator.translateTerms(prompt);

            List<TermTranslationResult> results=objectMapper.readValue(
                    json,
                    new TypeReference<List<TermTranslationResult>>(){}
            );

            validateTermResults(terms,results);

            Map<Integer,String> originals=createTermMap(terms);

            for(TermTranslationResult result:results){
                repository.updateYeastKo(
                        originals.get(result.getId()),
                        cleanResult(result.getValueKo())
                );
            }

            complete+=results.size();

            System.out.println(
                    "[YEAST] "+complete+"/"+total+
                            " | 남음="+repository.countUntranslatedYeast()
            );
        }

        System.out.println("SAKE 효모 번역 완료");
    }

    private List<TermTranslationData> createTerms(List<String> values){
        List<TermTranslationData> terms=new ArrayList<>();

        for(int i=0;i<values.size();i++){
            terms.add(new TermTranslationData(
                    i+1,
                    values.get(i)
            ));
        }

        return terms;
    }

    private Map<Integer,String> createTermMap(
            List<TermTranslationData> terms
    ){
        Map<Integer,String> map=new HashMap<>();

        for(TermTranslationData term:terms){
            map.put(term.getId(),term.getValue());
        }

        return map;
    }

    // JSON 파싱 성공만으로 충분하지 않아 요청 no의 누락·중복·빈 번역을 저장 전에 막는다.
    private void validateNameResults(
            List<SakeTranslationData> sakes,
            List<SakeTranslationResult> results
    ){
        if(results.size()!=sakes.size()){
            throw new IllegalStateException(
                    "Gemini SAKE 응답 개수 불일치: 요청="+
                            sakes.size()+", 응답="+results.size()
            );
        }

        Set<Long> requested=new HashSet<>();

        for(SakeTranslationData sake:sakes){
            requested.add(sake.getNo());
        }

        Set<Long> returned=new HashSet<>();

        for(SakeTranslationResult result:results){
            if(!requested.contains(result.getNo())){
                throw new IllegalStateException(
                        "요청하지 않은 SAKE no 반환: "+
                                result.getNo()
                );
            }

            if(!returned.add(result.getNo())){
                throw new IllegalStateException(
                        "중복 SAKE no 반환: "+
                                result.getNo()
                );
            }

            if(result.getNameKo()==null || result.getNameKo().isBlank()){
                throw new IllegalStateException(
                        "빈 한국어 사케명 반환: "+
                                result.getNo()
                );
            }
        }

        if(returned.size()!=requested.size()){
            throw new IllegalStateException(
                    "일부 SAKE 번역 결과가 누락되었습니다."
            );
        }
    }

    private void validateTermResults(
            List<TermTranslationData> terms,
            List<TermTranslationResult> results
    ){
        if(results.size()!=terms.size()){
            throw new IllegalStateException(
                    "Gemini 용어 응답 개수 불일치: 요청="+
                            terms.size()+", 응답="+results.size()
            );
        }

        Set<Integer> requested=new HashSet<>();

        for(TermTranslationData term:terms){
            requested.add(term.getId());
        }

        Set<Integer> returned=new HashSet<>();

        for(TermTranslationResult result:results){
            if(!requested.contains(result.getId())){
                throw new IllegalStateException(
                        "요청하지 않은 용어 id 반환: "+
                                result.getId()
                );
            }

            if(!returned.add(result.getId())){
                throw new IllegalStateException(
                        "중복 용어 id 반환: "+
                                result.getId()
                );
            }

            if(result.getValueKo()==null ||
                    result.getValueKo().isBlank()){
                throw new IllegalStateException(
                        "빈 용어 번역 반환: "+
                                result.getId()
                );
            }
        }

        if(returned.size()!=requested.size()){
            throw new IllegalStateException(
                    "일부 용어 번역 결과가 누락되었습니다."
            );
        }
    }

    private String createNamePrompt(
            List<SakeTranslationData> sakes
    ){
        StringBuilder data=new StringBuilder();

        for(int i=0;i<sakes.size();i++){
            SakeTranslationData sake=sakes.get(i);

            data.append("""
                {
                    "no":%d,
                    "nameJa":"%s",
                    "nameKana":"%s"
                }
                """.formatted(
                    sake.getNo(),
                    escapeJson(cleanHtml(sake.getNameJa())),
                    escapeJson(cleanHtml(sake.getNameKana()))
            ));

            if(i<sakes.size()-1){
                data.append(",");
            }
        }

        return """
            일본 사케 제품명을 한국어로 표기하는 데이터 정제 작업이다.

            다음 규칙을 반드시 지켜라.

            1. 브랜드명과 제품 고유명사는 의미를 임의로 번역하지 말고
               일본어 발음을 기준으로 한글로 표기한다.

            2. nameKana는 발음을 판단하기 위한 참고 자료다.

            3. nameKana가 제품명의 일부만 나타내는 경우가 있으므로
               nameKana에 없는 nameJa의 나머지 정보를 삭제하지 않는다.

            4. 일반적인 사케 용어는 한국에서 통용되는 표기를 사용한다.

               純米 → 준마이
               吟醸 → 긴죠
               大吟醸 → 다이긴죠
               本醸造 → 혼죠조
               特別純米 → 특별 준마이
               特別本醸造 → 특별 혼죠조
               生酒 → 나마자케
               生原酒 → 나마겐슈
               原酒 → 겐슈
               にごり → 니고리
               古酒 → 코슈
               貴醸酒 → 키죠슈

            5. 일본어 장음은 생략하지 않고 한글에서도
               발음이 드러나도록 표기한다.

               おおみね → 오오미네
               こう → 코우
               ほう → 호우

            6. 촉음과 요음도 일본어 발음을 최대한 보존한다.

            7. 숫자, 퍼센트, 영문 알파벳, 제품 코드,
               빈티지 연도 등은 특별한 이유가 없으면 원문을 유지한다.

            8. 원문에 없는 의미나 정보를 추가하지 않는다.

            9. 일본어 조사나 연결 표현도 의미 번역으로 바꾸지 말고
               제품명 일부라면 발음을 기준으로 표기한다.
               예: の → 노

            10. 입력된 no는 절대 변경하지 않는다.

            11. 모든 입력 항목을 정확히 한 번씩 반환한다.

            12. 결과에는 최종 한국어 제품명만 넣고
                설명이나 괄호 해설을 추가하지 않는다.

            번역할 데이터:
            [
            %s
            ]
            """.formatted(data);
    }

    private String createRicePrompt(
            List<TermTranslationData> terms
    ){
        return """
            일본 사케 데이터의 원료 쌀 정보를 한국어로 표기하는
            데이터 정제 작업이다.

            다음 규칙을 반드시 지켜라.

            1. 일본의 쌀 품종명은 사케 분야에서 일반적으로 사용되는
               한국어 표기가 있으면 그 표기를 사용한다.

            2. 일반적인 한국어 표기가 없으면 일본어 발음을 기준으로
               한글로 표기한다.

            3. 대표적인 품종은 다음 표기를 사용한다.

               山田錦 → 야마다니시키
               五百万石 → 고햐쿠만고쿠
               美山錦 → 미야마니시키
               雄町 → 오마치
               愛山 → 아이야마
               出羽燦々 → 데와산산
               八反錦 → 핫탄니시키
               吟風 → 긴푸
               彗星 → 스이세이

            4. 품종명이 아닌 일반적인 설명은 자연스러운 한국어로
               번역한다.

               酒造好適米 → 주조호적미
               国産米 → 국산 쌀
               掛米 → 카케마이
               麹米 → 코지마이

            5. 여러 품종이나 정보가 함께 적혀 있으면
               모든 정보를 유지한다.

            6. 산지, 비율, 퍼센트, 숫자 등의 정보를 삭제하지 않는다.

            7. 숫자와 퍼센트는 원문을 유지한다.

            8. 원문에 없는 품종이나 정보를 추측해서 추가하지 않는다.

            9. id는 절대 변경하지 않는다.

            10. 모든 입력 항목을 정확히 한 번씩 반환한다.

            11. 결과에는 한국어 표기만 넣고 설명을 추가하지 않는다.

            번역할 데이터:
            %s
            """.formatted(createTermJson(terms));
    }

    private String createYeastPrompt(
            List<TermTranslationData> terms
    ){
        return """
            일본 사케 데이터의 효모 정보를 한국어로 표기하는
            데이터 정제 작업이다.

            다음 규칙을 반드시 지켜라.

            1. 효모 종류에 대한 일반적인 설명은 자연스러운
               한국어로 번역한다.

               協会酵母 → 협회 효모
               自社酵母 → 자사 효모

            2. 協会9号, K-7, 1801号 등의 고유 번호와
               영문 코드는 그대로 보존한다.

            3. 효모의 고유명칭은 일반적으로 사용되는 한국어 표기가
               있으면 그 표기를 사용한다.

            4. 일반적인 한국어 표기가 없으면 일본어 발음을 기준으로
               한글로 표기한다.

            5. 여러 효모가 함께 적혀 있으면 모든 정보를 유지한다.

            6. 숫자, 기호, 영문 코드는 삭제하거나 변경하지 않는다.

            7. 원문에 없는 정보를 추측해서 추가하지 않는다.

            8. id는 절대 변경하지 않는다.

            9. 모든 입력 항목을 정확히 한 번씩 반환한다.

            10. 결과에는 한국어 표기만 넣고 설명을 추가하지 않는다.

            번역할 데이터:
            %s
            """.formatted(createTermJson(terms));
    }

    private String createTermJson(
            List<TermTranslationData> terms
    ){
        StringBuilder data=new StringBuilder();
        data.append("[\n");

        for(int i=0;i<terms.size();i++){
            TermTranslationData term=terms.get(i);

            data.append("""
                {
                    "id":%d,
                    "value":"%s"
                }
                """.formatted(
                    term.getId(),
                    escapeJson(term.getValue())
            ));

            if(i<terms.size()-1){
                data.append(",");
            }
        }

        data.append("]");

        return data.toString();
    }

    private String cleanHtml(String value){
        if(value==null) return "";

        String source=value
                .replaceAll("(?is)<br\\s*/?>"," / ")
                .replaceAll("(?s)<!--.*?-->","");

        Document document=Jsoup.parseBodyFragment(source);

        Elements elements=document.body().select("*");

        for(Element element:elements){
            if(element.tagName().equals("p") ||
                    element.tagName().equals("div") ||
                    element.tagName().equals("li")){
                element.after(" / ");
            }
        }

        String cleaned=document.body().text();

        return cleaned
                .replaceAll("\\s*/\\s*"," / ")
                .replaceAll("(\\s*/\\s*){2,}"," / ")
                .replaceAll("\\s+"," ")
                .replaceAll("^\\s*/\\s*","")
                .replaceAll("\\s*/\\s*$","")
                .trim();
    }

    private String cleanResult(String value){
        if(value==null) return null;

        return value
                .replaceAll("\\s+"," ")
                .trim();
    }

    private String escapeJson(String value){
        if(value==null) return "";

        return value
                .replace("\\","\\\\")
                .replace("\"","\\\"")
                .replace("\n","\\n")
                .replace("\r","\\r")
                .replace("\t","\\t");
    }
}