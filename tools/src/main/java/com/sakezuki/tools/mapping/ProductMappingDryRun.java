package com.sakezuki.tools.mapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ProductMappingDryRun {
    public record ListingInput(String pageUrl, String html) {}
    public record Input(List<ProductMapping.Legacy> legacy, List<ProductMapping.Candidate> candidates,
                        List<ListingInput> listings) {}
    public record ListingResult(int inputIndex, String pageUrl, ProductLinks.Listing parsed, String rejection) {}
    public record Output(ProductMapping.Report mapping, List<ListingResult> listings) {}

    private ProductMappingDryRun() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: productMappingDryRun --args='/path/to/input.json'");
        }
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var input = mapper.readValue(Path.of(args[0]).toFile(), Input.class);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(run(input)));
    }

    static Output run(Input input) {
        if (input == null || input.legacy() == null || input.candidates() == null) {
            throw new IllegalArgumentException("legacy and candidates arrays are required");
        }
        var results = new ArrayList<ListingResult>();
        if (input.listings() != null) {
            for (int i = 0; i < input.listings().size(); i++) {
                var listing = input.listings().get(i);
                if (listing == null) {
                    results.add(new ListingResult(i, null, null, "missing_listing"));
                    continue;
                }
                try {
                    results.add(new ListingResult(i, listing.pageUrl(),
                            ProductLinks.listing(listing.pageUrl(), listing.html()), null));
                } catch (IllegalArgumentException ex) {
                    results.add(new ListingResult(i, listing.pageUrl(), null, ex.getMessage()));
                }
            }
        }
        return new Output(ProductMapping.review(input.legacy(), input.candidates()), List.copyOf(results));
    }
}
