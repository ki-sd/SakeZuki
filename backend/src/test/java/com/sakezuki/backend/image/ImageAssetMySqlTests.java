package com.sakezuki.backend.image;

import com.sakezuki.backend.recommend.mapper.RecommendMapper;
import com.sakezuki.backend.sake.service.SakeService;
import com.sakezuki.imagecore.ImageFailure;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.ai.google.genai.api-key=test",
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ImageAssetMySqlTests {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("AUTH_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("AUTH_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("AUTH_TEST_DB_PASSWORD"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageFixture {
        @Bean @Primary
        FakeStores imageFixtureStores() {
            return new FakeStores();
        }
    }

    @LocalServerPort int port;
    @Autowired ImageAssetService assets;
    @Autowired ImagePublicationService publication;
    @Autowired RecommendationImageProjection projection;
    @Autowired FakeStores stores;
    @Autowired JdbcTemplate db;
    @Autowired SakeService sakes;
    @Autowired RecommendMapper recommendations;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void stagedAssetIsPrivateAndVerifiedDeliveryNeedsAnExplicitLinkAndLiveRights() throws Exception {
        long sake = newSake();
        String legacy = "https://legacy.example.invalid/image.png";
        assertEquals(legacy, sakes.getSakeDetail(sake).getImageUrl());
        UUID id = stage();
        String path = url(sake, id);
        assertEquals(404, get(path).statusCode());
        assertThrows(IllegalStateException.class, () -> assets.prepareDelivery(id));
        grant(id);
        assertThrows(IllegalStateException.class, () -> publication.publishPrimary(sake, id));
        assets.prepareDelivery(id);
        assertEquals(404, get(path).statusCode());
        publication.publishPrimary(sake, id);
        publication.publishPrimary(sake, id);
        assertEquals(1, links(sake));
        assertEquals(path, sakes.getSakeDetail(sake).getImageUrl());
        String search = sakes.getSakeDetail(sake).getNameJa();
        assertEquals(path, sakes.searchSakeList(search).getFirst().getImageUrl());
        assertEquals(path, sakes.getSakeList(1, search, null).getFirst().getImageUrl());
        db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,?)", search, sake, "fixture");
        assertEquals(path, recommendations.getSakeRecommendList(search).getFirst().getImageUrl());

        var response = get(path);
        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(response.body().length, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals(db.queryForObject("SELECT transformed_sha256 FROM IMAGE_ASSET WHERE id=?", String.class,
                id.toString()), ImageTransform.sha256(response.body()));
        assertNotEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());

        String legacySnapshot = "{\"sake\":{\"no\":" + sake + ",\"imageUrl\":\"" + legacy + "\"},\"recommendations\":[{\"no\":"
                + sake + ",\"imageUrl\":\"" + path + "\",\"reason\":\"keep\"}]}";
        assertFalse(projection.currentImages(legacySnapshot).contains(legacy));
        publication.recordRights(id, ImagePublicationService.Decision.REVOKED, "rights:revoked", "fixture", null, null);
        assertEquals(404, get(path).statusCode());
        assertNull(sakes.getSakeDetail(sake).getImageUrl());
        assertNull(recommendations.getSakeRecommendList(search).getFirst().getImageUrl());
        assertFalse(projection.currentImages(legacySnapshot).contains(legacy));
        assertFalse(projection.currentImages(legacySnapshot).contains(path));
        assertTrue(projection.currentImages(legacySnapshot).contains("\"reason\":\"keep\""));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?", Integer.class, id.toString()));
        grant(id);
        assertEquals(404, get(path).statusCode());
    }

    @Test
    void storageFailureAndWrongMetadataDoNotPublishAndSameRequestRecoversOnce() throws Exception {
        UUID id = UUID.randomUUID();
        var staging = stores.staging;
        byte[] png = png();
        staging.beforePut = () -> { throw new IOException("fixture outage"); };
        try {
            assertThrows(IOException.class, () -> stage(id, png));
            assertEquals("RETRYABLE_FAILURE", state(id, "STAGING"));
        } finally {
            staging.beforePut = () -> {};
        }
        stage(id, png);
        stage(id, png);
        assertEquals("VERIFIED", state(id, "STAGING"));
        String key = key(id, "STAGING");
        assertEquals(1, staging.writes.get(key).get());
        assertThrows(IllegalStateException.class, () ->
                assets.stage(id, ImageAssetService.Source.USER_SUBMISSION, "different evidence", "image/png", png));

        long sake = newSake();
        grant(id);
        var staged = staging.objects.get(key);
        stores.delivery.objects.put(key, new ImageStorage.Payload("image/jpeg", staged.cacheControl(), staged.declaredSha256(), staged.bytes()));
        assertThrows(ImageFailure.class, () -> assets.prepareDelivery(id));
        assertEquals("PERMANENT_FAILURE", state(id, "DELIVERY"));
        assertThrows(IllegalStateException.class, () -> publication.publishPrimary(sake, id));
        assertEquals(404, get(url(sake, id)).statusCode());
        assertNull(stores.delivery.writes.get(key));
        assertEquals("image/jpeg", stores.delivery.objects.get(key).contentType());
    }

    @Test
    void successfulPutFollowedByDatabaseFailureIsReconciledWithoutSecondPut() throws Exception {
        UUID id = UUID.randomUUID();
        db.execute("""
                ALTER TABLE IMAGE_OBJECT ADD CONSTRAINT image_fixture_finalize_failure
                CHECK (asset_id <> '%s' OR storage_state <> 'VERIFIED')
                """.formatted(id));
        byte[] bytes = png();
        try {
            assertThrows(DataAccessException.class, () -> stage(id, bytes));
            assertEquals("PENDING", state(id, "STAGING"));
            assertTrue(stores.staging.objects.containsKey(key(id, "STAGING")));
        } finally {
            db.execute("ALTER TABLE IMAGE_OBJECT DROP CHECK image_fixture_finalize_failure");
        }
        stage(id, bytes);
        assertEquals("VERIFIED", state(id, "STAGING"));
        assertEquals(1, stores.staging.writes.get(key(id, "STAGING")).get());
    }

    @Test
    void compositeForeignKeysAndConcurrentPublicationKeepOneActivePrimary() throws Exception {
        long sake = newSake();
        UUID first = ready();
        UUID second = ready();
        Long firstRights = db.queryForObject("SELECT id FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?", Long.class, first.toString());
        Long secondObject = db.queryForObject("SELECT id FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area='DELIVERY'",
                Long.class, second.toString());
        assertThrows(DataAccessException.class, () -> db.update("""
                INSERT INTO SAKE_IMAGE(sake_no,asset_id,object_id,rights_event_id,is_primary) VALUES(?,?,?,?,TRUE)
                """, sake, first.toString(), secondObject, firstRights));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); publication.publishPrimary(sake, first); return null; });
            var two = pool.submit(() -> { start.await(); publication.publishPrimary(sake, second); return null; });
            start.countDown();
            one.get(15, TimeUnit.SECONDS);
            two.get(15, TimeUnit.SECONDS);
        }
        assertEquals(2, links(sake));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM PUBLIC_SAKE_IMAGE WHERE sake_no=?", Integer.class, sake));
        assertThrows(DataAccessException.class, () -> db.update("""
                INSERT INTO SAKE_IMAGE(sake_no,asset_id,object_id,rights_event_id,is_primary)
                SELECT sake_no,asset_id,object_id,rights_event_id,TRUE FROM SAKE_IMAGE WHERE active_primary_sake=?
                """, sake));
    }

    @Test
    void corruptedDeliveryAndRevocationDuringGetDoNotSendBytes() throws Exception {
        long sake = newSake();
        UUID id = ready();
        publication.publishPrimary(sake, id);
        String path = url(sake, id);
        String key = key(id, "DELIVERY");
        var original = stores.delivery.objects.get(key);
        byte[] corrupt = original.bytes().clone();
        corrupt[0] = 0;
        stores.delivery.objects.put(key, new ImageStorage.Payload(original.contentType(), original.cacheControl(), original.declaredSha256(), corrupt));
        var failed = get(path);
        assertEquals(503, failed.statusCode());
        assertEquals(0, failed.body().length);
        stores.delivery.objects.put(key, original);

        stores.delivery.beforeRead = () -> publication.recordRights(id, ImagePublicationService.Decision.REVOKED,
                "rights:concurrent", "fixture", null, null);
        try {
            var response = get(path);
            assertEquals(404, response.statusCode());
            assertEquals(0, response.body().length);
        } finally {
            stores.delivery.beforeRead = () -> {};
        }
    }

    private long newSake() {
        String name = UUID.randomUUID().toString();
        db.update("INSERT INTO SAKE(brewery_no, name_ja, image_url) VALUES(1,?,?)",
                name, "https://legacy.example.invalid/image.png");
        return db.queryForObject("SELECT no FROM SAKE WHERE name_ja=?", Long.class, name);
    }

    private static String url(long sake, UUID id) { return "/api/images/sakes/" + sake + "/" + id; }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private int links(long sake) {
        return db.queryForObject("SELECT COUNT(*) FROM SAKE_IMAGE WHERE sake_no=?", Integer.class, sake);
    }

    private String key(UUID id, String area) {
        return db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area=?", String.class,
                id.toString(), area);
    }

    private String state(UUID id, String area) {
        return db.queryForObject("SELECT storage_state FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area=?",
                String.class, id.toString(), area);
    }

    private UUID ready() throws Exception {
        UUID id = stage();
        grant(id);
        assets.prepareDelivery(id);
        return id;
    }

    private void grant(UUID id) {
        publication.recordRights(id, ImagePublicationService.Decision.GRANTED, "rights:fixture", "fixture",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
    }

    private UUID stage() throws Exception {
        UUID id = UUID.randomUUID();
        stage(id, png());
        return id;
    }

    private void stage(UUID id, byte[] png) throws Exception {
        assets.stage(id, ImageAssetService.Source.USER_SUBMISSION, "submission:fixture", "image/png", png);
    }

    private static byte[] png() throws IOException {
        var image = new BufferedImage(20, 12, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff00ff00);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    static final class FakeStores implements ImageStores {
        final MemoryStore staging = new MemoryStore();
        final MemoryStore delivery = new MemoryStore();

        @Override public Optional<ImageStorage.Readable> storage(Area area) {
            return Optional.of(area == Area.STAGING ? staging : delivery);
        }
    }

    static final class MemoryStore implements ImageStorage.Readable {
        final Map<String, ImageStorage.Payload> objects = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> writes = new ConcurrentHashMap<>();
        volatile Probe beforePut = () -> {};
        volatile Probe beforeRead = () -> {};

        @Override public boolean createIfAbsent(ObjectKey key, String mime, String hash, byte[] bytes) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            beforePut.run();
            boolean created = objects.putIfAbsent(key.value(), new Payload(mime, CACHE_CONTROL, hash, bytes.clone())) == null;
            if (created) writes.computeIfAbsent(key.value(), ignored -> new AtomicInteger()).incrementAndGet();
            return created;
        }

        @Override public Optional<ObjectInfo> read(ObjectKey key, int maxBytes) throws IOException {
            return readPayload(key, maxBytes).map(value -> new ObjectInfo(value.contentType(), value.cacheControl(),
                    value.declaredSha256(), ImageTransform.sha256(value.bytes()), value.bytes().length));
        }

        @Override public Optional<Payload> readPayload(ObjectKey key, int maxBytes) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            beforeRead.run();
            var value = objects.get(key.value());
            if (value == null) return Optional.empty();
            if (value.bytes().length > maxBytes) throw new ReadLimitExceeded();
            return Optional.of(new Payload(value.contentType(), value.cacheControl(), value.declaredSha256(), value.bytes().clone()));
        }
    }

    @FunctionalInterface
    interface Probe {
        void run() throws IOException;
    }
}
