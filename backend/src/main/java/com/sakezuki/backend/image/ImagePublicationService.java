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
    public void recordRights(UUID asset, Decision decision, String evidence, String actor, Instant from, Instant until) {
        ImageAssetService.requireRef(evidence, 500);
        ImageAssetService.requireRef(actor, 200);
        Objects.requireNonNull(decision);
        if (decision == Decision.GRANTED ? from == null || (until != null && !until.isAfter(from))
                : from != null || until != null) {
            throw new IllegalArgumentException("invalid image rights validity");
        }
        assets.lockAsset(asset);
        assets.recordRights(asset, decision, evidence, actor, from, until);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishPrimary(long sakeNo, UUID asset) {
        assets.lockAsset(asset);
        assets.attachPrimary(sakeNo, asset);
    }
}
