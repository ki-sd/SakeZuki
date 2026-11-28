package com.sakezuki.backend.image;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Internal operations. Future callers must supply an authorized server-side decision and actor.
@Service
public class ImagePublicationService {
    public enum Decision { GRANTED, DENIED, REVOKED }

    private final ImageAssetRepository assets;

    ImagePublicationService(ImageAssetRepository assets) {
        this.assets = assets;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long recordRights(UUID asset, Decision decision, String evidence, String actor, Instant from, Instant until) {
        ImageAssetService.requireRef(evidence, 500);
        ImageAssetService.requireRef(actor, 200);
        Objects.requireNonNull(decision);
        if (decision == Decision.GRANTED ? from == null || (until != null && !until.isAfter(from))
                : from != null || until != null) {
            throw new IllegalArgumentException("invalid image rights validity");
        }
        assets.lockAsset(asset);
        return assets.recordRights(asset, decision, evidence, actor, from, until);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ImageAssetService.ObjectVersion lockVerifiedSubmissionStaging(UUID asset) {
        var image = assets.lockAsset(asset);
        var source = assets.object(asset, ImageStores.Area.STAGING);
        if (!"USER_SUBMISSION".equals(image.source()) || !assets.verifiedStaging(asset,source.id(),source.attempt())) {
            throw new ImageAssetService.PromotionRejected("STAGING_NOT_READY");
        }
        return new ImageAssetService.ObjectVersion(source.id(),source.attempt());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void publishPrimary(long sakeNo, UUID asset, long grant, ImageAssetService.ObjectVersion delivery) {
        assets.lockAsset(asset);
        var object = assets.object(asset,ImageStores.Area.DELIVERY);
        if (object.id()!=delivery.id() || object.attempt()!=delivery.attempt() || !assets.hasGrant(asset,grant)) {
            throw new ImageAssetService.PromotionRejected("PUBLICATION_PRECONDITION_CHANGED");
        }
        assets.attachPrimary(sakeNo, asset, delivery.id(), grant);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishPrimary(long sakeNo, UUID asset) {
        assets.lockAsset(asset);
        assets.attachPrimary(sakeNo, asset);
    }
}
