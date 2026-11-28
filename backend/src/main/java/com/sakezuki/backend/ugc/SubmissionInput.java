package com.sakezuki.backend.ugc;

import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

record SubmissionInput(Names sake, Choice brewery, Choice brand, String designation,
                       Measurement sakeMeter, Measurement acidity, Measurement polishingPercent,
                       Measurement alcoholPercent, String riceJa, String riceKo, String yeastJa, String yeastKo,
                       Integer capacityMl, String variant, String sourceUrl, String notes,
                       UUID attachmentId, String imageRights, Boolean imageConsent) {
    SubmissionInput {
        if (notes != null) notes = notes.replace("\r\n", "\n");
        if (imageRights != null) imageRights = imageRights.replace("\r\n", "\n");
    }

    record Names(String ja, String ko, String kana) {}
    record Choice(Long existingNo, Names proposed) {}
    record Measurement(BigDecimal min, BigDecimal max) {}
    record Revision(Long expectedVersion, SubmissionInput data) {}
    record Cancel(Long expectedVersion, String reason) {}

    void validate() {
        names(sake, "sake");
        choice(brewery, true, "brewery");
        choice(brand, false, "brand");
        if (brand != null && brand.existingNo() != null && brewery.existingNo() == null) throw invalid("brand");
        text(designation, 100, false, "designation");
        measurement(sakeMeter, -1000, 1000, "sakeMeter");
        measurement(acidity, 0, 100, "acidity");
        measurement(polishingPercent, 0, 100, "polishingPercent");
        measurement(alcoholPercent, 0, 100, "alcoholPercent");
        text(riceJa, 500, false, "riceJa");
        text(riceKo, 500, false, "riceKo");
        text(yeastJa, 500, false, "yeastJa");
        text(yeastKo, 500, false, "yeastKo");
        if (capacityMl != null && (capacityMl < 1 || capacityMl > 100_000)) throw invalid("capacityMl");
        text(variant, 500, false, "variant");
        text(notes, 2000, false, "notes", true);
        text(sourceUrl, 1000, false, "sourceUrl");
        if (sourceUrl != null) validateUrl(sourceUrl);
        text(imageRights, 1000, attachmentId != null, "imageRights", true);
        if (attachmentId != null && !Boolean.TRUE.equals(imageConsent)) throw invalid("imageConsent");
        if (attachmentId == null && (imageRights != null || imageConsent != null)) throw invalid("attachmentId");
    }

    private static void names(Names names, String field) {
        if (names == null) throw invalid(field);
        text(names.ja(), 500, true, field + ".ja");
        text(names.ko(), 1000, true, field + ".ko");
        text(names.kana(), 500, false, field + ".kana");
    }

    private static void choice(Choice value, boolean required, String field) {
        if (value == null && !required) return;
        if (value == null || (value.existingNo() == null) == (value.proposed() == null)) throw invalid(field);
        if (value.existingNo() != null && value.existingNo() < 1) throw invalid(field);
        if (value.proposed() != null) names(value.proposed(), field);
    }

    static void text(String value, int max, boolean required, String field) {
        text(value, max, required, field, false);
    }

    private static void text(String value, int max, boolean required, String field, boolean multiline) {
        if (value == null && !required) return;
        if (value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.codePointCount(0, value.length()) > max
                || value.codePoints().anyMatch(c -> (Character.isISOControl(c) && !(multiline && c == '\n'))
                        || c >= 0xd800 && c <= 0xdfff)) {
            throw invalid(field);
        }
    }

    private static void measurement(Measurement value, int lower, int upper, String field) {
        if (value == null) return;
        decimal(value.min(), lower, upper, field);
        if (value.max() != null) {
            decimal(value.max(), lower, upper, field);
            if (value.max().compareTo(value.min()) < 0) throw invalid(field);
        }
    }

    private static void decimal(BigDecimal value, int lower, int upper, String field) {
        if (value == null || value.scale() < 0 || value.scale() > 3
                || value.compareTo(BigDecimal.valueOf(lower)) < 0 || value.compareTo(BigDecimal.valueOf(upper)) > 0) {
            throw invalid(field);
        }
    }

    private static void validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid("sourceUrl");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() > 65535) {
            throw invalid("sourceUrl");
        }
    }

    static ResponseStatusException invalid(String field) {
        return SubmissionErrors.error(422, "입력값을 확인해 주세요 (" + field + ").");
    }
}
