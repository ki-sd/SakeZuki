package com.sakezuki.tools.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductMappingDryRunTest {
    @Test
    void syntheticFixtureProducesVersionedReviewAndListingResults() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/mapping/synthetic-input.json")) {
            var input = mapper.readValue(stream, ProductMappingDryRun.Input.class);
            var output = mapper.valueToTree(ProductMappingDryRun.run(input));
            assertTrue(output.at("/mapping/dryRun").asBoolean());
            assertEquals(1, output.at("/mapping/schemaVersion").asInt());
            assertEquals("ONE_TO_ONE_REVIEW", output.at("/mapping/rows/0/status").asText());
            assertEquals("UNMATCHED", output.at("/mapping/rows/1/status").asText());
            assertEquals("fixture-101", output.at("/listings/0/parsed/links/0/pathKey").asText());
        }
    }

    @Test
    void missingRequiredArraysFailAndBadListingsAreReported() {
        assertThrows(IllegalArgumentException.class,
                () -> ProductMappingDryRun.run(new ProductMappingDryRun.Input(null, List.of(), null)));
        var badListing = new ProductMappingDryRun.ListingInput("https://example.invalid/sake", "<html></html>");
        var output = ProductMappingDryRun.run(new ProductMappingDryRun.Input(List.of(), List.of(), List.of(badListing)));
        assertEquals("invalid_source_origin", output.listings().getFirst().rejection());
    }
}
