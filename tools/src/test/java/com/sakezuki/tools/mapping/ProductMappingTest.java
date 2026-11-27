package com.sakezuki.tools.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static com.sakezuki.tools.mapping.ProductMapping.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductMappingTest {
    private static final String URL = "https://sake-guide.com/sake/example-brand-101";
    private static final ProductMapping.Facts FACTS =
            new ProductMapping.Facts("架空酒 純米", "架空酒造", "架空酒", "純米酒", "55%", "720 mL・火入れ");
    private static final ProductMapping.Legacy LEGACY = new ProductMapping.Legacy(13L, 101L, FACTS);

    @Test
    void normalizedFactsAndObservedUrlProduceReviewOnly() {
        var japaneseWidth = new ProductMapping.Facts("架空酒　純米", "架空酒造", "架空酒", "純米酒", "５５ ％", "７２０ ｍＬ・火入れ");
        var row = row(candidate(URL, japaneseWidth));
        assertEquals(ONE_TO_ONE_REVIEW, row.status());
        assertEquals(101L, row.sourceId());
        assertEquals(URL, row.candidates().getFirst().link().url());
        assertEquals("matching_fields_pending_human_review", row.reasons().getFirst());
    }

    @Test
    void equalFactsCannotOverrideDifferentId() {
        assertEquals(ID_MISMATCH, row(candidate(URL.replace("101", "102"), FACTS)).status());
    }

    @Test
    void sameNameDifferentBreweryOrBrandIsNotTheSameProduct() {
        var other = new ProductMapping.Facts(FACTS.name(), "他の蔵元", "他の銘柄", "純米酒", "55%", FACTS.variant());
        assertEquals(UNMATCHED, row(candidate(URL.replace("101", "102"), other)).status());
        var conflict = row(candidate(URL, other));
        assertEquals(IDENTIFIER_COLLISION, conflict.status());
        assertEquals(List.of("brewery", "brand"), conflict.candidates().getFirst().differences());
    }

    @Test
    void designationPolishAndVariantDifferencesExcludeCandidate() {
        var variant = new ProductMapping.Facts(FACTS.name(), FACTS.brewery(), FACTS.brand(),
                "特別純米酒", "50%", "1800 mL・生酒");
        var conflict = row(candidate(URL, variant));
        assertEquals(IDENTIFIER_COLLISION, conflict.status());
        assertEquals(List.of("designation", "polishingRatio", "variant"),
                conflict.candidates().getFirst().differences());
    }

    @Test
    void repeatedPathAndRepeatedLegacyIdentityAreReported() {
        assertEquals(DUPLICATE, row(candidate(URL, FACTS), candidate(URL + "?ref=list", FACTS)).status());
        var report = ProductMapping.review(List.of(LEGACY, new ProductMapping.Legacy(18L, 101L, FACTS)),
                List.of(candidate(URL, FACTS)));
        assertTrue(report.rows().stream().allMatch(row -> row.status() == DUPLICATE));
    }

    @Test
    void conflictingPathsForSameNumericIdAreReported() {
        assertEquals(IDENTIFIER_COLLISION,
                row(candidate(URL, FACTS), candidate(URL.replace("example-brand", "second-slug"), FACTS)).status());
    }

    @Test
    void differentIdsWithSameFactsNeedReviewForAmbiguity() {
        assertEquals(AMBIGUOUS,
                row(candidate(URL, FACTS), candidate(URL.replace("101", "102"), FACTS)).status());
    }

    @Test
    void sameCandidateCannotBeOneToOneWithTwoCompatibleLegacyRows() {
        var report = ProductMapping.review(List.of(LEGACY, new ProductMapping.Legacy(18L, 102L, FACTS)),
                List.of(candidate(URL, FACTS)));
        assertTrue(report.rows().stream().allMatch(row -> row.status() == AMBIGUOUS));
    }

    @Test
    void duplicateOrCollidingAlternateIdStillNeedsDistinctReview() {
        String alternateUrl = URL.replace("101", "102");
        assertEquals(DUPLICATE, row(candidate(alternateUrl, FACTS), candidate(alternateUrl, FACTS)).status());
        assertEquals(IDENTIFIER_COLLISION, row(candidate(alternateUrl, FACTS),
                candidate(alternateUrl.replace("example-brand", "second-slug"), FACTS)).status());
    }

    @Test
    void absentCandidatesAreUnmatched() {
        assertEquals(UNMATCHED, row().status());
    }

    @Test
    void incompleteLegacyOrCandidateFactsAndEvidenceCannotProduceOneToOne() {
        var missingVariant = new ProductMapping.Facts(FACTS.name(), FACTS.brewery(), FACTS.brand(),
                FACTS.designation(), FACTS.polishingRatio(), null);
        var output = row(new ProductMapping.Candidate(URL, null, missingVariant, null));
        assertEquals(INCOMPLETE, output.status());
        assertEquals(List.of("variant", "evidenceRef"), output.candidates().getFirst().missingFields());
        assertEquals(INCOMPLETE, ProductMapping.review(
                List.of(new ProductMapping.Legacy(13L, 101L, missingVariant)),
                List.of(candidate(URL, FACTS))).rows().getFirst().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://sake-guide.com/sake/example-101", "https://sake-guide.com.evil.test/sake/example-101",
            "https://sake-guide.com@evil.test/sake/example-101", "https://user@sake-guide.com/sake/example-101",
            "https://sake-guide.com:81/sake/example-101", "https://sake-guide.com/sake/detail/101",
            "https://sake-guide.com/sake/example-%31%30%31", "https://sake-guide.com/sake/example-99999999999999999999",
            "https://sake-guide.com/sake/example-0", "https://sake-guide.com/sake/example-101/extra"
    })
    void invalidUrlIsRejectedWithInputIndex(String invalid) {
        var report = ProductMapping.review(List.of(LEGACY), List.of(candidate(invalid, FACTS)));
        assertEquals(UNMATCHED, report.rows().getFirst().status());
        var rejection = assertDoesNotThrow(() -> report.rejectedCandidates().getFirst());
        assertEquals(0, rejection.inputIndex());
        assertEquals(invalid, rejection.url());
        assertFalse(rejection.reason().isBlank());
    }

    private static ProductMapping.Row row(ProductMapping.Candidate... candidates) {
        return ProductMapping.review(List.of(LEGACY), List.of(candidates)).rows().getFirst();
    }

    private static ProductMapping.Candidate candidate(String url, ProductMapping.Facts facts) {
        return new ProductMapping.Candidate(url, null, facts, "fixtures/product.html");
    }
}
