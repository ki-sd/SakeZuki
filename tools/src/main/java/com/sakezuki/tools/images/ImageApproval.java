package com.sakezuki.tools.images;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

import static com.sakezuki.tools.images.ImagePipeline.Status.*;

public final class ImageApproval {
    public record Mapping(long no, long sourceId, String sourceSystem, String pathKey, URI detailUrl,
                          String evidenceRef, String reviewedBy, Instant reviewedAt) {}
    public record Request(Mapping mapping, URI sourceImageUrl, String rightsEvidenceRef) {}
    public enum RightsStatus { UNKNOWN, DENIED, AUTHORIZED }
    public enum Use { REPRODUCE, TRANSFORM, STORE, REDISTRIBUTE }
    public record Rights(RightsStatus status, String sourceSystem, Set<URI> permittedUrls, String evidenceRef,
                         Set<Use> uses, Instant validFrom, Instant validUntil, boolean revoked) {
        public Rights {
            permittedUrls = permittedUrls == null ? null : Set.copyOf(permittedUrls);
            uses = uses == null ? null : Set.copyOf(uses);
        }
    }
    public record Review(Mapping approvedMapping, Rights rights) {}

    @FunctionalInterface
    public interface Authority {
        // This lookup belongs to a trusted review store, independent of submitted input.
        Review lookup(long no, long sourceId, URI sourceImageUrl) throws IOException;
    }

    private ImageApproval() {}

    static Rights require(Request request, Authority authority, Instant now) throws ImagePipeline.Failure {
        Mapping supplied = request == null ? null : request.mapping();
        if (supplied == null || supplied.no() <= 0 || supplied.sourceId() <= 0 || blank(supplied.sourceSystem())
                || blank(supplied.pathKey()) || !validDetailUrl(supplied.detailUrl()) || blank(supplied.evidenceRef())
                || blank(supplied.reviewedBy()) || supplied.reviewedAt() == null || supplied.reviewedAt().isAfter(now)) {
            throw new ImagePipeline.Failure(APPROVAL_PENDING, "complete_mapping_review_required");
        }
        if (request.sourceImageUrl() == null) {
            throw new ImagePipeline.Failure(RIGHTS_UNCONFIRMED, "source_image_url_missing");
        }
        Review review;
        try {
            review = authority.lookup(supplied.no(), supplied.sourceId(), request.sourceImageUrl());
        } catch (IOException ex) {
            throw new ImagePipeline.Failure(APPROVAL_PENDING, "trusted_review_unavailable");
        }
        if (review == null || !supplied.equals(review.approvedMapping())) {
            throw new ImagePipeline.Failure(APPROVAL_PENDING, "trusted_mapping_approval_missing");
        }
        Rights grant = review.rights();
        if (grant != null && (grant.revoked() || grant.status() == RightsStatus.DENIED)) {
            throw new ImagePipeline.Failure(RIGHTS_DENIED, "grant_denied_or_revoked");
        }
        if (grant == null || grant.status() != RightsStatus.AUTHORIZED || blank(request.rightsEvidenceRef())
                || !request.rightsEvidenceRef().equals(grant.evidenceRef())
                || !supplied.sourceSystem().equals(grant.sourceSystem()) || grant.permittedUrls() == null
                || !grant.permittedUrls().contains(request.sourceImageUrl()) || grant.uses() == null
                || !grant.uses().containsAll(Set.of(Use.REPRODUCE, Use.TRANSFORM, Use.STORE, Use.REDISTRIBUTE))
                || grant.validFrom() == null || grant.validUntil() == null
                || now.isBefore(grant.validFrom()) || !now.isBefore(grant.validUntil())) {
            throw new ImagePipeline.Failure(RIGHTS_UNCONFIRMED, "valid_scoped_grant_required");
        }
        return grant;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean validDetailUrl(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getFragment() == null;
    }
}
