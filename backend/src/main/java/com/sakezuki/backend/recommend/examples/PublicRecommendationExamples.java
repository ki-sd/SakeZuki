package com.sakezuki.backend.recommend.examples;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/examples/recommendations")
public class PublicRecommendationExamples {
    public record Product(long no, String nameKo, String nameJa, String sakeType, String reason) {}
    public record Food(String name, String reason) {}
    public record Example(String slug, String title, String kind, String inputLabel, String sourceType,
                          String sourceNote, List<Product> sakes, List<Food> foods, Product selectedSake) {}
    private final List<Example> examples;

    public PublicRecommendationExamples(ObjectMapper json) throws IOException {
        // 공개 전 검토된 리소스만 읽는다. 추천 서비스나 개인 기록 Mapper를 주입하지 않아
        // 방문자의 조회가 Gemini 생성·예약 차감·개인정보 공개로 이어질 경로를 만들지 않는다.
        try (var stream = new ClassPathResource("examples/recommendations.json").getInputStream()) {
            examples = List.of(json.readValue(stream, Example[].class));
        }
    }

    @GetMapping
    public ResponseEntity<List<Example>> list() { return response(examples); }

    @GetMapping("/{slug}")
    public ResponseEntity<Example> detail(@PathVariable String slug) {
        return response(examples.stream().filter(example -> example.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공개 예시를 찾을 수 없습니다.")));
    }
    private <T> ResponseEntity<T> response(T body) {
        // 인증 응답의 공용 캐싱을 피하려고 보안 체인의 기본 no-store 정책도 유지한다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
