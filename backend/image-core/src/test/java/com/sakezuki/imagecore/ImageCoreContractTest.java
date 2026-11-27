package com.sakezuki.imagecore;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

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

    @Test
    void outputIsImmutableAndAReflectivelyForgedPayloadCannotBeStored() throws Exception {
        byte[] input = ImageFixtures.png(12, 12);
        var output = ImageTransform.convert(ImageTransform.PNG, input, Set.of());
        var media = output.transformed();
        Arrays.fill(input, (byte) 0);
        Arrays.fill(output.bytes(), (byte) 0);
        assertEquals(media.sha256(), ImageTransform.sha256(output.bytes()));
        assertEquals(0, ImageTransform.Output.class.getConstructors().length);

        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        var forgedMedia = new ImageTransform.Info(ImageTransform.PNG, html.length, 12, 12, ImageTransform.sha256(html));
        var constructor = ImageTransform.Output.class.getDeclaredConstructor(
                ImageTransform.Info.class, ImageTransform.Info.class, byte[].class);
        constructor.setAccessible(true);
        var forged = constructor.newInstance(forgedMedia, forgedMedia, html);
        var store = new MemoryStorage();
        assertThrows(ImageFailure.class, () ->
                VerifiedImageStorage.store(store, ImageStorage.ObjectKey.forImage(9, forgedMedia), forged));
        assertEquals(0, store.writes);
        assertThrows(IllegalArgumentException.class, () ->
                ImageStorage.ObjectKey.forImage(9, new ImageTransform.Info("image/webp", 1, 1, 1, media.sha256())));
        assertThrows(IllegalArgumentException.class, () ->
                ImageStorage.ObjectKey.forAsset(java.util.UUID.randomUUID(), "../image-v1", media));
    }

    @Test
    void rejectsPngCrcAndAnimationChunks() throws Exception {
        byte[] png = ImageFixtures.png(10, 10);
        byte[] corrupt = png.clone();
        corrupt[29] ^= 1; // IHDR CRC
        assertInvalid(ImageTransform.PNG, corrupt);
        var animated = new ByteArrayOutputStream();
        animated.write(png, 0, 33);
        animated.write(pngChunk("acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array()));
        animated.write(png, 33, png.length - 33);
        assertInvalid(ImageTransform.PNG, animated.toByteArray());
    }

    @Test
    void rejectsResolutionPixelBudgetOversizedBytesAndWrongMime() throws Exception {
        byte[] png = ImageFixtures.png(10, 10);
        assertInvalid(ImageTransform.PNG, withPngDimensions(png, 8193, 1));
        assertInvalid(ImageTransform.PNG, withPngDimensions(png, 4001, 4000));
        assertInvalid(ImageTransform.PNG, new byte[ImageTransform.MAX_BYTES + 1]);
        assertInvalid("text/html", png);
        var jpeg = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg));
        assertInvalid(ImageTransform.PNG, jpeg.toByteArray());
    }

    private static byte[] withPngDimensions(byte[] png, int width, int height) {
        byte[] header = Arrays.copyOfRange(png, 16, 29);
        ByteBuffer.wrap(header).putInt(width).putInt(height);
        byte[] changed = png.clone();
        System.arraycopy(pngChunk("IHDR", header), 0, changed, 8, 25);
        return changed;
    }

    private static byte[] pngChunk(String name, byte[] body) {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(body);
        return ByteBuffer.allocate(12 + body.length).putInt(body.length).put(type).put(body)
                .putInt((int) crc.getValue()).array();
    }

    private static void assertInvalid(String mime, byte[] data) {
        assertEquals(ImageFailure.Category.INVALID_IMAGE,
                assertThrows(ImageFailure.class, () -> ImageTransform.convert(mime, data, Set.of())).category());
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
