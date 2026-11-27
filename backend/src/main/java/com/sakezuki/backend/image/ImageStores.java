package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageStorage;

import java.util.Optional;

public interface ImageStores {
    enum Area { STAGING, DELIVERY }

    Optional<ImageStorage.Readable> storage(Area area);
}
