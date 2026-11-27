package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.VerifiedImageStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@RestController
public class PublicSakeImageController {
    private final ImageAssetRepository assets;
    private final ImageStores stores;
    private final Semaphore deliveries = new Semaphore(4);

    PublicSakeImageController(ImageAssetRepository assets, ImageStores stores) {
        this.assets = assets;
        this.stores = stores;
    }

    @GetMapping("/api/images/sakes/{sakeNo}/{asset}")
    public void image(@PathVariable long sakeNo, @PathVariable UUID asset, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!deliveries.tryAcquire()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        try {
            var candidate = assets.publicImage(sakeNo, asset);
            if (candidate.isEmpty()) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            var storage = stores.storage(ImageStores.Area.DELIVERY);
            if (storage.isEmpty()) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return;
            }
            var image = candidate.get();
            byte[] bytes;
            try {
                bytes = VerifiedImageStorage.readVerified(storage.get(), new ImageStorage.ObjectKey(image.objectKey()), image.media());
            } catch (IOException | IllegalArgumentException failure) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return;
            }
            // Recheck after I/O, including a concurrent replacement or revocation.
            if (!candidate.equals(assets.publicImage(sakeNo, asset))) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            response.setContentType(image.media().mimeType());
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } finally {
            deliveries.release();
        }
    }
}
