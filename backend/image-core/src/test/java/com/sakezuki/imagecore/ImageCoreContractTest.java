package com.sakezuki.imagecore;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImageCoreContractTest {
    @Test
    void pngOutputKeepsAlphaAndHashesBothOriginalAndResizedBytes() throws Exception {
        byte[] original = ImageFixtures.png(1500, 750);
        var output = ImageTransform.convert(ImageTransform.PNG, original, Set.of());
        assertEquals(1500, output.original().width());
        assertEquals(1200, output.transformed().width());
        assertEquals(600, output.transformed().height());
        assertEquals(ImageTransform.sha256(original), output.original().sha256());
        assertEquals(ImageTransform.sha256(output.bytes()), output.transformed().sha256());
        assertEquals(output.bytes().length, output.transformed().byteLength());
        var image = ImageIO.read(new ByteArrayInputStream(output.bytes()));
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0, image.getRGB(0, 0) >>> 24);
    }

    @Test
    void commonFailureCategoriesDistinguishInvalidImageAndKnownPlaceholder() throws Exception {
        byte[] original = ImageFixtures.png(8, 8);
        assertEquals(ImageFailure.Category.INVALID_IMAGE, assertThrows(ImageFailure.class,
                () -> ImageTransform.convert(ImageTransform.JPEG, original, Set.of())).category());
        assertEquals(ImageFailure.Category.IMAGE_ABSENT, assertThrows(ImageFailure.class,
                () -> ImageTransform.convert(ImageTransform.PNG, original,
                        Set.of(ImageTransform.sha256(original)))).category());
    }

    @Test
    void verifiedStoreReusesExistingObjectAndRejectsWrongReadBackDigest() throws Exception {
        var output = ImageTransform.convert(ImageTransform.PNG, ImageFixtures.png(10, 10), Set.of());
        var info = output.transformed();
        var key = ImageStorage.ObjectKey.forImage(23, info);
        assertEquals("sake/23/image-v1/" + info.sha256() + ".png", key.value());
        var storage = new MemoryStorage();
        assertTrue(VerifiedImageStorage.store(storage, key, output));
        assertFalse(VerifiedImageStorage.store(storage, key, output));
        storage.corrupted = true;
        assertEquals(ImageFailure.Category.STORAGE_FAILED, assertThrows(ImageFailure.class,
                () -> VerifiedImageStorage.store(storage, key, output)).category());
        assertEquals(1, storage.writes);
    }

    private static final class MemoryStorage implements ImageStorage {
        private int writes;
        private boolean corrupted;
        private ObjectInfo object;

        @Override
        public boolean createIfAbsent(ObjectKey key, String type, String hash, byte[] bytes) throws IOException {
            writes++;
            assertNull(object);
            object = new ObjectInfo(type, CACHE_CONTROL, hash, ImageTransform.sha256(bytes), bytes.length);
            return true;
        }

        @Override
        public Optional<ObjectInfo> read(ObjectKey key, int maxBytes) {
            if (corrupted) {
                return Optional.of(new ObjectInfo(object.contentType(), object.cacheControl(), object.declaredSha256(),
                        "corrupt", object.byteLength()));
            }
            return Optional.ofNullable(object);
        }
    }
}
