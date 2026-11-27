package com.sakezuki.tools.mapping;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ProductMapping {
    public enum Status {
        ONE_TO_ONE_REVIEW, ID_MISMATCH, IDENTIFIER_COLLISION, DUPLICATE,
        AMBIGUOUS, UNMATCHED, INCOMPLETE
    }

    public record Facts(String name, String brewery, String brand, String designation,
                        String polishingRatio, String variant) {}
    public record Legacy(Long no, Long sourceId, Facts facts) {}
    public record Candidate(String url, String detailHtml, Facts facts, String evidenceRef) {}
    public record Rejection(int inputIndex, String url, String reason) {}
    public record Evidence(ProductLinks.Link link, Facts facts, String evidenceRef,
                           List<String> missingFields, List<String> differences) {}
    public record Row(Long no, Long sourceId, Facts legacyFacts, Status status,
                      List<String> reasons, List<Evidence> candidates) {}
    public record Report(int schemaVersion, boolean dryRun, List<Row> rows, List<Rejection> rejectedCandidates) {}

    private record Parsed(ProductLinks.Link link, Candidate candidate) {}

    private ProductMapping() {}

    public static Report review(List<Legacy> legacy, List<Candidate> candidates) {
        List<Rejection> rejected = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            try {
                if (candidate == null) {
                    throw new IllegalArgumentException("missing_candidate");
                }
                parsed.add(new Parsed(ProductLinks.detail(candidate.url(), candidate.detailHtml()), candidate));
            } catch (IllegalArgumentException ex) {
                rejected.add(new Rejection(i, candidate == null ? null : candidate.url(), ex.getMessage()));
            }
        }

        var byId = parsed.stream().collect(Collectors.groupingBy(p -> p.link().numericId()));
        var byName = parsed.stream().filter(p -> !name(p.candidate().facts()).isEmpty())
                .collect(Collectors.groupingBy(p -> name(p.candidate().facts())));
        var legacyNoCounts = counts(legacy, Legacy::no);
        var legacyIdCounts = counts(legacy, Legacy::sourceId);
        var legacyFactCounts = counts(legacy, item -> values(item.facts()));
        var rows = new ArrayList<Row>();

        for (Legacy item : legacy) {
            if (item == null || item.no() == null || item.no() <= 0 || item.sourceId() == null || item.sourceId() <= 0) {
                rows.add(new Row(item == null ? null : item.no(), item == null ? null : item.sourceId(),
                        item == null ? null : item.facts(), Status.INCOMPLETE, List.of("missing_or_invalid_legacy_identity"), List.of()));
                continue;
            }

            var sameId = byId.getOrDefault(item.sourceId(), List.of());
            var relevant = new ArrayList<>(sameId);
            for (Parsed other : byName.getOrDefault(name(item.facts()), List.of())) {
                if (other.link().numericId() != item.sourceId()) {
                    relevant.add(other);
                }
            }
            var fields = values(item.facts());
            var evidence = relevant.stream().map(other -> compare(fields, other)).toList();
            var idEvidence = evidence.subList(0, sameId.size());
            var plausible = evidence.stream().filter(e -> e.differences().isEmpty()).toList();
            var result = classify(legacyNoCounts.get(item.no()), legacyIdCounts.get(item.sourceId()),
                    fields, legacyFactCounts.get(fields), idEvidence, plausible);
            rows.add(new Row(item.no(), item.sourceId(), item.facts(), result.status(), List.of(result.reason()), evidence));
        }
        return new Report(1, true, List.copyOf(rows), List.copyOf(rejected));
    }

    private record Decision(Status status, String reason) {}

    private static Decision classify(long sameLegacyNo, long sameLegacyId, Map<String, String> legacy, long sameLegacyFacts,
                                     List<Evidence> sameId, List<Evidence> plausible) {
        if (sameLegacyNo > 1 || sameLegacyId > 1) {
            return new Decision(Status.DUPLICATE, "repeated_legacy_identity");
        }
        if (sameId.stream().anyMatch(e -> !e.differences().isEmpty())) {
            return new Decision(Status.IDENTIFIER_COLLISION, "same_numeric_id_conflicting_product_facts");
        }
        if (sameId.stream().map(e -> e.link().pathKey()).distinct().count() > 1) {
            return new Decision(Status.IDENTIFIER_COLLISION, "multiple_paths_for_numeric_id");
        }
        if (sameId.stream().map(e -> values(e.facts())).distinct().count() > 1) {
            return new Decision(Status.IDENTIFIER_COLLISION, "conflicting_records_for_same_path");
        }
        if (sameId.size() > 1) {
            return new Decision(Status.DUPLICATE, "repeated_candidate_link");
        }
        if (plausible.size() > 1 && plausible.stream().map(e -> e.link().numericId()).distinct().count() == 1) {
            if (plausible.stream().map(e -> e.link().pathKey()).distinct().count() > 1
                    || plausible.stream().map(e -> values(e.facts())).distinct().count() > 1) {
                return new Decision(Status.IDENTIFIER_COLLISION, "conflicting_candidates_for_alternate_numeric_id");
            }
            return new Decision(Status.DUPLICATE, "repeated_candidate_link");
        }
        if (plausible.size() > 1) {
            return new Decision(Status.AMBIGUOUS, "multiple_candidates_without_conflicting_facts");
        }
        if (legacy.containsValue("")) {
            return new Decision(Status.INCOMPLETE, "missing_legacy_product_facts");
        }
        if (plausible.isEmpty()) {
            return new Decision(Status.UNMATCHED, "no_compatible_product");
        }
        if (!plausible.getFirst().missingFields().isEmpty()) {
            return new Decision(Status.INCOMPLETE, "missing_candidate_facts_or_evidence");
        }
        if (sameLegacyFacts > 1) {
            return new Decision(Status.AMBIGUOUS, "same_product_facts_on_multiple_legacy_rows");
        }
        if (sameId.isEmpty()) {
            return new Decision(Status.ID_MISMATCH, "different_numeric_id_with_matching_product_facts");
        }
        return new Decision(Status.ONE_TO_ONE_REVIEW, "matching_fields_pending_human_review");
    }

    private static <K> Map<K, Long> counts(List<Legacy> legacy, Function<Legacy, K> key) {
        return legacy.stream().filter(item -> item != null && key.apply(item) != null)
                .collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    private static Evidence compare(Map<String, String> fields, Parsed other) {
        var candidate = values(other.candidate().facts());
        var missing = new LinkedHashSet<String>();
        var differences = new ArrayList<String>();
        fields.forEach((field, value) -> {
            String candidateValue = candidate.get(field);
            if (value.isEmpty() || candidateValue.isEmpty()) {
                missing.add(field);
            } else if (!value.equals(candidateValue)) {
                differences.add(field);
            }
        });
        if (normalize(other.candidate().evidenceRef()).isEmpty()) {
            missing.add("evidenceRef");
        }
        return new Evidence(other.link(), other.candidate().facts(), other.candidate().evidenceRef(),
                List.copyOf(missing), List.copyOf(differences));
    }

    private static String name(Facts facts) {
        return facts == null ? "" : normalize(facts.name());
    }

    private static LinkedHashMap<String, String> values(Facts facts) {
        var values = new LinkedHashMap<String, String>();
        values.put("name", name(facts));
        values.put("brewery", facts == null ? "" : normalize(facts.brewery()));
        values.put("brand", facts == null ? "" : normalize(facts.brand()));
        values.put("designation", facts == null ? "" : normalize(facts.designation()));
        values.put("polishingRatio", facts == null ? "" : normalize(facts.polishingRatio()).replaceFirst(" *%$", "%"));
        values.put("variant", facts == null ? "" : normalize(facts.variant()));
        return values;
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
