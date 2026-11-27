package com.sakezuki.tools.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductLinksTest {
    private static final String BASE = "https://sake-guide.com";
    private static final String PRODUCT = BASE + "/sake/example-brand-101";

    @Test
    void listingReadsActualLinksAndIgnoresHtmlBaseAndNonProductPaths() {
        var listing = ProductLinks.listing(BASE + "/sake", """
                <base href="https://example.invalid">
                <a href="/sake/example-brand-101?ref=list#label">架空酒</a>
                <a href="sake/another-102">別の商品</a>
                <a href="/sake/example-brand-101">繰り返し</a>
                <a href="/sake/detail/101">旧リンク</a>
                <a href="/ja/contact">問い合わせ</a>
                <a href="https://example.invalid/sake/example-101">外部</a>
                """);
        assertEquals(List.of(101L, 102L, 101L), listing.links().stream().map(ProductLinks.Link::numericId).toList());
        assertEquals(PRODUCT, listing.links().getFirst().url());
        assertEquals("example-brand-101", listing.links().getFirst().pathKey());
        assertEquals("invalid_source_origin", listing.rejectedLinks().getFirst().reason());
    }

    @Test
    void detailAcceptsSameCanonicalIdentity() {
        var link = ProductLinks.detail(PRODUCT, """
                <link rel="canonical" href="/sake/example-brand-101/">
                """);
        assertEquals(101L, link.numericId());
        assertEquals(PRODUCT, link.url());
    }

    @Test
    void detailRejectsConflictingCanonical() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ProductLinks.detail(PRODUCT, """
                <link rel="canonical" href="/sake/other-brand-101">
                """));
        assertEquals("canonical_conflict", ex.getMessage());
    }
}
