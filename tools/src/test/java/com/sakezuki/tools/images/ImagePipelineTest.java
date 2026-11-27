package com.sakezuki.tools.images;

import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static com.sakezuki.tools.images.ImageApproval.*;
import static com.sakezuki.tools.images.ImagePipeline.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ImagePipelineTest {
    private static final Instant NOW = Instant.parse("2026-11-27T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI SOURCE = URI.create("https://images.example.test/bottle.png");
    private static final URI REDIRECT = URI.create("https://cdn.example.test/bottle.png");
    private static final Mapping MAPPING = new Mapping(32, 101, "synthetic", "demo-101",
            URI.create("https://products.example.test/sake/demo-101"), "mapping-evidence", "reviewer", NOW.minusSeconds(1));
    private static final Request REQUEST = new Request(MAPPING, SOURCE, "synthetic-grant");

    @Test
    void approvalAndRightsAreIndependentAndProtectBothNetworkAndStorage() {
        SecureImageDownload.Transport unused = (url, ip) -> fail("unauthorized HTTP request");
        var store = new FakeStorage();
        assertEquals(APPROVAL_PENDING, pipeline((no, id, url) -> null, unused, store).process(REQUEST).status());
        assertEquals("trusted_review_unavailable", pipeline((no, id, url) -> { throw new IOException("offline"); },
                unused, store).process(REQUEST).reason());
        assertEquals(APPROVAL_PENDING, pipeline((no, id, url) -> new Review(null, grant()), unused, store).process(REQUEST).status());
        assertEquals(RIGHTS_UNCONFIRMED, pipeline((no, id, url) -> new Review(MAPPING, null), unused, store).process(REQUEST).status());
        var revoked = new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(SOURCE), "synthetic-grant",
                Set.of(Use.values()), NOW.minusSeconds(10), NOW.plusSeconds(10), true);
        assertEquals(RIGHTS_DENIED, pipeline(authority(revoked), unused, store).process(REQUEST).status());
        var expired = new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(SOURCE), "synthetic-grant",
                Set.of(Use.values()), NOW.minusSeconds(10), NOW, false);
        assertEquals(RIGHTS_UNCONFIRMED, pipeline(authority(expired), unused, store).process(REQUEST).status());
        var narrow = new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(SOURCE), "synthetic-grant",
                Set.of(Use.REPRODUCE), NOW.minusSeconds(10), NOW.plusSeconds(10), false);
        assertEquals(RIGHTS_UNCONFIRMED, pipeline(authority(narrow), unused, store).process(REQUEST).status());
        assertEquals(RIGHTS_UNCONFIRMED, pipeline(authority(grant()), unused, store)
                .process(new Request(MAPPING, SOURCE, "wrong-grant")).status());
        assertEquals(0, store.writes);
    }

    @Test
    void checksGrantAgainBeforeStorage() throws IOException {
        byte[] image = image("png", 20, 20);
        var checks = new AtomicInteger();
        Authority authority = (no, id, url) -> new Review(MAPPING, checks.getAndIncrement() == 0 ? grant() : null);
        var store = new FakeStorage();
        assertEquals(RIGHTS_UNCONFIRMED, pipeline(authority, (u, ip) -> success("image/png", image), store)
                .process(REQUEST).status());
        assertEquals(0, store.writes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.9", "169.254.169.254",
            "100.100.100.200", "168.63.129.16", "0.0.0.0", "224.0.0.1", "::", "::1",
            "fe80::1", "fd00:ec2::254", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1"})
    void rejectsLocalSpecialOrTransitionAddressesFromAnyDnsAnswer(String address) throws Exception {
        var rejected = InetAddress.getByName(address);
        var downloader = downloader(host -> List.of(publicIp(), rejected),
                (url, ip) -> fail("unsafe DNS set reached transport"), new ArrayList<>());
        assertEquals(INVALID_SOURCE, new ImagePipeline(authority(grant()), downloader, new FakeStorage(), CLOCK, Set.of())
                .process(REQUEST).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://images.example.test/bottle.png", "https://other.example.test/bottle.png",
            "https://user@images.example.test/bottle.png", "https://images.example.test:8443/bottle.png",
            "https://images.example.test/a/%2e%2e/bottle.png", "https://127.0.0.1/bottle.png"})
    void deniesSpecialOrUnapprovedUrlEvenWithAnExactGrant(String address) {
        URI uri = URI.create(address);
        var scoped = new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(uri), "synthetic-grant",
                Set.of(Use.values()), NOW.minusSeconds(10), NOW.plusSeconds(10), false);
        assertEquals(INVALID_SOURCE, pipeline(authority(scoped), (u, ip) -> fail("invalid URL reached transport"), new FakeStorage())
                .process(new Request(MAPPING, uri, "synthetic-grant")).status());
    }

    @Test
    void redirectsResolveAgainAndPassOnlyPinnedAddressToTransport() throws Exception {
        var first = publicIp();
        var second = InetAddress.getByName("2606:4700:4700::1111");
        byte[] image = image("png", 20, 20);
        var downloader = downloader(host -> List.of(host.equals(SOURCE.getHost()) ? first : second),
                (uri, pinned) -> {
                    if (uri.equals(SOURCE)) {
                        assertEquals(first, pinned);
                        return response(302, Map.of("Location", REDIRECT.toString()), new byte[0]);
                    }
                    assertEquals(REDIRECT, uri);
                    assertEquals(second, pinned);
                    return success("image/png", image);
                }, new ArrayList<>());
        var result = new ImagePipeline(authority(grant()), downloader, new FakeStorage(), CLOCK, Set.of()).process(REQUEST);
        assertEquals(STORED_VERIFIED, result.status());
        assertEquals(REDIRECT, result.asset().finalImageUrl());
    }

    @Test
    void redirectMustRemainInRightsScopeAndResolveToPublicAddress() {
        var scoped = new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(SOURCE), "synthetic-grant",
                Set.of(Use.values()), NOW.minusSeconds(10), NOW.plusSeconds(10), false);
        SecureImageDownload.Transport transport = (uri, pinned) -> {
            assertEquals(SOURCE, uri);
            return response(307, Map.of("Location", REDIRECT.toString()), new byte[0]);
        };
        assertEquals(RIGHTS_UNCONFIRMED, pipeline(authority(scoped), transport, new FakeStorage()).process(REQUEST).status());
        var downloader = downloader(host -> List.of(host.equals(SOURCE.getHost()) ? publicIp() : InetAddress.getLoopbackAddress()),
                transport, new ArrayList<>());
        assertEquals(INVALID_SOURCE, new ImagePipeline(authority(grant()), downloader, new FakeStorage(), CLOCK, Set.of())
                .process(REQUEST).status());
    }

    @Test
    void absenceFailureAndRetryAfterAreDistinct() {
        var delays = new ArrayList<Duration>();
        var attempts = new AtomicInteger();
        var downloader = downloader(host -> List.of(publicIp()), (u, ip) -> {
            attempts.incrementAndGet();
            return response(429, Map.of("Retry-After", "2"), new byte[0]);
        }, delays);
        var result = new ImagePipeline(authority(grant()), downloader, new FakeStorage(), CLOCK, Set.of()).process(REQUEST);
        assertEquals(DOWNLOAD_FAILED, result.status());
        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2)), delays);
        assertEquals(Duration.ofSeconds(2), result.retryAfter());
        assertEquals(IMAGE_ABSENT, pipeline(authority(grant()), (u, ip) -> response(404, Map.of(), new byte[0]),
                new FakeStorage()).process(REQUEST).status());

        attempts.set(0);
        delays.clear();
        var deferred = downloader(host -> List.of(publicIp()), (u, ip) -> {
            attempts.incrementAndGet();
            return response(503, Map.of("Retry-After", "Fri, 27 Nov 2026 04:00:30 GMT"), new byte[0]);
        }, delays);
        var deferredResult = new ImagePipeline(authority(grant()), deferred, new FakeStorage(), CLOCK, Set.of()).process(REQUEST);
        assertEquals(DOWNLOAD_FAILED, deferredResult.status());
        assertEquals(Duration.ofSeconds(30), deferredResult.retryAfter());
        assertEquals(1, attempts.get());
        assertTrue(delays.isEmpty());
    }

    @Test
    void capsDeclaredAndStreamedDownloadAndRejectsEncodedBody() {
        var store = new FakeStorage();
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> response(200,
                Map.of("Content-Type", "image/png", "Content-Length", "999999999"), new byte[0]), store).process(REQUEST).status());
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> success("image/png",
                new byte[ImageTransform.MAX_BYTES + 1]), store).process(REQUEST).status());
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> response(200,
                Map.of("Content-Type", "image/png", "Content-Encoding", "gzip"), new byte[0]), store).process(REQUEST).status());
        assertEquals(0, store.writes);
    }

    @Test
    void verifiesMimeIntegrityResolutionAndKnownPlaceholder() throws IOException {
        byte[] png = image("png", 120, 60);
        byte[] tooWide = image("png", ImageTransform.MAX_EDGE + 1, 1);
        var store = new FakeStorage();
        for (String mime : List.of("image/jpeg", "text/html", "image/svg+xml", "image/webp")) {
            assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> success(mime, png), store)
                    .process(REQUEST).status());
        }
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> success("image/png", Arrays.copyOf(png, png.length - 5)),
                store).process(REQUEST).status());
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> success("image/png", tooWide), store)
                .process(REQUEST).status());
        var placeholder = new ImagePipeline(authority(grant()),
                downloader(host -> List.of(publicIp()), (u, ip) -> success("image/png", png), new ArrayList<>()),
                store, CLOCK, Set.of(ImageTransform.sha256(png)));
        assertEquals(IMAGE_ABSENT, placeholder.process(REQUEST).status());
        assertEquals(0, store.writes);
    }

    @Test
    void rejectsPixelBombBeforeDecodingAndMalformedJpegWithValidMarkers() throws IOException {
        byte[] png = image("png", 2, 2);
        ByteBuffer.wrap(png).putInt(16, 5000).putInt(20, 5000);
        var crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        var large = pipeline(authority(grant()), (u, ip) -> success("image/png", png), new FakeStorage()).process(REQUEST);
        assertEquals(INVALID_IMAGE, large.status());
        assertEquals("image_resolution_limit", large.reason());
        byte[] corrupted = {(byte) 255, (byte) 216, (byte) 255, 0, (byte) 255, (byte) 217};
        assertEquals(INVALID_IMAGE, pipeline(authority(grant()), (u, ip) -> success("image/jpeg", corrupted),
                new FakeStorage()).process(REQUEST).status());
    }

    @Test
    void generatesDeterministicKeyAndVerifiesImageAndObjectBeforeReusing() throws IOException {
        byte[] png = image("png", 1600, 800);
        var store = new FakeStorage();
        var pipeline = pipeline(authority(grant()), (u, ip) -> success("image/png", png), store);
        var first = pipeline.process(REQUEST);
        assertEquals(STORED_VERIFIED, first.status());
        var asset = first.asset();
        assertEquals(1600, asset.original().width());
        assertEquals(ImageTransform.sha256(png), asset.original().sha256());
        assertEquals(1200, asset.transformed().width());
        assertEquals(600, asset.transformed().height());
        assertEquals("image-v1", asset.transformVersion());
        assertEquals("sake/32/image-v1/" + asset.transformed().sha256() + ".png", asset.objectKey().value());
        assertTrue(asset.newlyCreated());
        byte[] stored = store.objects.get(asset.objectKey()).bytes();
        assertEquals(ImageTransform.sha256(stored), asset.transformed().sha256());
        assertEquals(stored.length, asset.transformed().byteLength());
        assertEquals(0, ImageIO.read(new ByteArrayInputStream(stored)).getRGB(0, 0) >>> 24);
        var second = pipeline.process(REQUEST);
        assertEquals(STORED_VERIFIED, second.status());
        assertEquals(asset.objectKey(), second.asset().objectKey());
        assertFalse(second.asset().newlyCreated());
        assertEquals(1, store.writes);
    }

    @Test
    void jpegRoundTripDoesNotUpscaleAndPathInputIsStrict() throws IOException {
        byte[] jpeg = image("jpeg", 64, 32);
        var result = pipeline(authority(grant()), (u, ip) -> success("image/jpeg", jpeg), new FakeStorage()).process(REQUEST);
        assertEquals(STORED_VERIFIED, result.status());
        assertEquals(64, result.asset().transformed().width());
        assertEquals(32, result.asset().transformed().height());
        assertTrue(result.asset().objectKey().value().endsWith(".jpg"));
        assertThrows(IllegalArgumentException.class, () -> new ImageStorage.ObjectKey("../other"));
    }

    @Test
    void unavailableEncoderIsTransformFailureRatherThanMissingOrInvalidImage() throws IOException {
        byte[] jpeg = image("jpeg", 20, 20);
        var registry = IIORegistry.getDefaultInstance();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        var providers = new ArrayList<javax.imageio.spi.ImageWriterSpi>();
        while (writers.hasNext()) {
            var writer = writers.next();
            providers.add(writer.getOriginatingProvider());
            writer.dispose();
        }
        try {
            providers.forEach(registry::deregisterServiceProvider);
            var store = new FakeStorage();
            assertEquals(TRANSFORM_FAILED, pipeline(authority(grant()), (u, ip) -> success("image/jpeg", jpeg),
                    store).process(REQUEST).status());
            assertEquals(0, store.writes);
        } finally {
            providers.forEach(registry::registerServiceProvider);
        }
    }

    @Test
    void aConcurrentWriterIsReusedOnlyAfterVerification() throws IOException {
        byte[] png = image("png", 20, 20);
        var storage = new FakeStorage();
        storage.concurrentWriter = true;
        var result = pipeline(authority(grant()), (u, ip) -> success("image/png", png), storage).process(REQUEST);
        assertEquals(STORED_VERIFIED, result.status());
        assertFalse(result.asset().newlyCreated());
        assertEquals(1, storage.writes);
    }

    @Test
    void storageFailureAndWrongReadBackHashNeverReportSuccessfulAsset() throws IOException {
        byte[] bytes = image("png", 20, 20);
        var storage = new FakeStorage();
        var pipeline = pipeline(authority(grant()), (u, ip) -> success("image/png", bytes), storage);
        storage.failWrite = true;
        assertEquals(STORAGE_FAILED, pipeline.process(REQUEST).status());
        storage.failWrite = false;
        storage.corruptRead = true;
        var result = pipeline.process(REQUEST);
        assertEquals(STORAGE_FAILED, result.status());
        assertEquals("stored_object_integrity_mismatch", result.reason());
        assertNull(result.asset());
        assertEquals(1, storage.writes);
        assertEquals(STORAGE_FAILED, pipeline.process(REQUEST).status());
        assertEquals(1, storage.writes);
    }

    private static Rights grant() {
        return new Rights(RightsStatus.AUTHORIZED, "synthetic", Set.of(SOURCE, REDIRECT), "synthetic-grant",
                Set.of(Use.values()), NOW.minusSeconds(10), NOW.plusSeconds(10), false);
    }

    private static Authority authority(Rights rights) {
        return (no, id, url) -> new Review(MAPPING, rights);
    }

    private static ImagePipeline pipeline(Authority auth, SecureImageDownload.Transport transport, FakeStorage storage) {
        return new ImagePipeline(auth, downloader(host -> List.of(publicIp()), transport, new ArrayList<>()),
                storage, CLOCK, Set.of());
    }

    private static SecureImageDownload downloader(SecureImageDownload.Resolver resolver,
                                                  SecureImageDownload.Transport transport, List<Duration> waits) {
        return new SecureImageDownload(Set.of(SOURCE.getHost(), REDIRECT.getHost()), resolver, transport, CLOCK, waits::add);
    }

    private static InetAddress publicIp() throws IOException {
        return InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
    }

    static byte[] image(String format, int width, int height) throws IOException {
        var image = new BufferedImage(width, height,
                format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(width / 2, height / 2, 0xffcc0000);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }

    private static SecureImageDownload.Response success(String mime, byte[] bytes) {
        return response(200, Map.of("Content-Type", mime), bytes);
    }

    private static SecureImageDownload.Response response(int code, Map<String, String> headers, byte[] bytes) {
        return new SecureImageDownload.Response() {
            public int status() { return code; }
            public String header(String name) { return headers.get(name); }
            public InputStream body() { return new ByteArrayInputStream(bytes); }
            public void close() {}
        };
    }

    private record Saved(String mime, String hash, byte[] bytes) {}

    private static final class FakeStorage implements ImageStorage {
        final Map<ObjectKey, Saved> objects = new HashMap<>();
        int writes;
        boolean failWrite;
        boolean corruptRead;
        boolean concurrentWriter;

        public boolean createIfAbsent(ObjectKey key, String contentType, String sha256, byte[] bytes) throws IOException {
            if (failWrite) {
                throw new IOException("injected");
            }
            writes++;
            boolean inserted = objects.putIfAbsent(key, new Saved(contentType, sha256, bytes.clone())) == null;
            return inserted && !concurrentWriter;
        }

        public Optional<ObjectInfo> read(ObjectKey key, int maxBytes) {
            var item = objects.get(key);
            if (item == null) {
                return Optional.empty();
            }
            assertTrue(item.bytes().length <= maxBytes);
            return Optional.of(new ObjectInfo(item.mime(), CACHE_CONTROL, item.hash(),
                    corruptRead ? "different" : ImageTransform.sha256(item.bytes()), item.bytes().length));
        }
    }
}
