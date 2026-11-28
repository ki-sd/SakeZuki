package com.sakezuki.backend.ugc;

import com.sakezuki.backend.image.ImageStores;
import com.sakezuki.backend.security.CustomUserDetails;
import com.sakezuki.backend.security.jwt.JwtTokenProvider;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.ai.google.genai.api-key=test",
        "spring.security.oauth2.client.registration.google.client-id=test",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ugc.open-submissions=2", "ugc.uploads-per-day=2",
        "ugc.image-approval.enabled=true", "ugc.image-approval.poll-ms=3600000",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 4"})
class UgcMySqlTests {
    private static final String ROOT = UgcController.ROOT;
    private static final String ADMIN = AdminReviewController.ROOT;
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("uploadFixtureStore") FakeStore staging;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("deliveryFixtureStore") FakeStore delivery;
    @Autowired ImageApprovalJobs imageJobs;
    @Autowired ImageApprovalWorker imageWorker;
    @Autowired com.sakezuki.backend.image.ImagePublicationService publication;
    @Autowired ImageApprovalService imageApprovals;
    @Autowired AttachmentRepository attachments;
    @Autowired SubmissionRepository submissions;
    @Autowired SubmissionLimits limits;
    @Autowired com.sakezuki.backend.image.ImageAssetService images;
    @Autowired ImageStores stores;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.sakezuki.backend.recommend.mapper.RecommendMapper recommendations;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("AUTH_TEST_JDBC_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("AUTH_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("AUTH_TEST_DB_PASSWORD"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageFixture {
        @Bean FakeStore uploadFixtureStore() { return new FakeStore(); }
        @Bean FakeStore deliveryFixtureStore() { return new FakeStore(); }
        @Bean @Primary ImageStores uploadFixtureAreas(
                @org.springframework.beans.factory.annotation.Qualifier("uploadFixtureStore") FakeStore storage,
                @org.springframework.beans.factory.annotation.Qualifier("deliveryFixtureStore") FakeStore delivery) {
            return area -> Optional.of(area == ImageStores.Area.STAGING ? storage : delivery);
        }
    }

    @Test
    void ownersOnlyAndRevisionCancellationAreAuditedAndImmutable() throws Exception {
        var owner = member("ROLE_USER");
        var other = member("ROLE_ADMIN");
        var key = UUID.randomUUID();
        var data = draft(null);
        data.put("notes", "<script>fixture()</script>");
        var create = post(owner, ROOT, key, data);
        assertEquals(201, create.statusCode(), string(create));
        assertEquals(node(create), node(post(owner, ROOT, key, data)));
        String path = ROOT + "/" + node(create).get("id").asString();
        assertEquals(401, get(null, path).statusCode());
        assertEquals(404, get(other, path).statusCode());
        assertEquals(404, post(other, path + "/cancel", null, Map.of("expectedVersion", 0)).statusCode());
        assertEquals(404, post(other, path + "/revisions", null, Map.of("expectedVersion", 0, "data", data)).statusCode());
        assertEquals(403, post(owner, "/api/admin/sake-submissions/" + node(create).get("id").asString() + "/approve",
                null, Map.of()).statusCode());
        assertEquals(422, post(owner, ROOT, UUID.randomUUID(), Map.of("role", "ADMIN")).statusCode());
        var first = node(get(owner, path)).get("revisions").get(0).get("data");
        assertTrue(first.get("acidity").isNull());
        assertEquals(-12.5, first.get("sakeMeter").get("min").doubleValue());
        assertEquals("<script>fixture()</script>", first.get("notes").asString());
        data.put("notes", "changed");
        assertEquals(409, post(owner, ROOT, key, data).statusCode());
        String id = node(create).get("id").asString();
        db.update("UPDATE CATALOG_SUBMISSION SET status='NEEDS_REVISION',version=1 WHERE id=?", id);
        assertEquals(201, post(owner, path + "/revisions", null, Map.of("expectedVersion", 1, "data", data)).statusCode());
        assertEquals(409, post(owner, path + "/revisions", null, Map.of("expectedVersion", 1, "data", data)).statusCode());
        assertEquals(200, post(owner, path + "/cancel", null, Map.of("expectedVersion", 2)).statusCode());
        assertEquals(409, post(owner, path + "/cancel", null, Map.of("expectedVersion", 3)).statusCode());
        var detail = node(get(owner, path));
        assertEquals("CANCELLED", detail.get("submission").get("status").asString());
        assertEquals(3, detail.get("events").size());
        assertEquals(first, detail.get("revisions").get(0).get("data"));

        var otherSubmission = node(post(other, ROOT, key, draft(null))).get("id").asString();
        assertThrows(DataIntegrityViolationException.class,
                () -> db.update("UPDATE CATALOG_SUBMISSION SET current_revision_no=2 WHERE id=?", otherSubmission));
        db.update("UPDATE MEMBER SET status='INACTIVE' WHERE no=?", owner.no());
        assertEquals(401, get(owner, path).statusCode());
    }

    @Test
    void catalogOptionsAndInputConstraintsUseInternalIds() throws Exception {
        var owner = member("ROLE_USER");
        assertEquals(200, get(owner, "/api/user/catalog/breweries?size=2").statusCode());
        assertEquals(200, get(owner, "/api/user/catalog/brands?breweryNo=1&size=2").statusCode());
        assertEquals(422, get(owner, ROOT + "?size=51").statusCode());
        long unrelated = db.queryForObject("SELECT MIN(no) FROM BRAND WHERE brewery_no<>1", Long.class);
        var input = draft(null);
        input.put("brand", Map.of("existingNo", unrelated));
        assertInvalid(owner, input);
        input.remove("brand");
        input.put("sourceUrl", "javascript:fixture()");
        assertInvalid(owner, input);
        input.remove("sourceUrl");
        input.put("notes", "bad\u0000control");
        assertInvalid(owner, input);
        input.put("notes", "unicode-fixture");
        String malformed = json.writeValueAsString(input).replace("unicode-fixture", "\\u" + "d800");
        var badUnicode = request(owner, ROOT).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(malformed)).build();
        assertEquals(422, http.send(badUnicode, HttpResponse.BodyHandlers.discarding()).statusCode());
        input.put("notes", "x".repeat(2001));
        assertInvalid(owner, input);
        input.put("notes", "oversized " + "가".repeat(8000));
        assertEquals(413, post(owner, ROOT, UUID.randomUUID(), input).statusCode());
        input.remove("notes");
        input.put("alcoholPercent", Map.of("min", 100.01));
        assertInvalid(owner, input);
        input.remove("alcoholPercent");
        input.put("brewery", Map.of("proposed", Map.of("ja", "合成蔵", "ko", "합성 양조장")));
        assertEquals(201, post(owner, ROOT, UUID.randomUUID(), input).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE name_ja='合成酒'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM BREWERY WHERE name_ja='合成蔵'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no=?", Integer.class, owner.no()));
    }

    @Test
    void bothImageFormatsArePrivateAndAttachmentIsExclusiveToOneSubmission() throws Exception {
        var owner = member("ROLE_USER");
        var other = member("ROLE_USER");
        var pngKey = UUID.randomUUID();
        var png = image("png");
        var result = upload(owner, pngKey, "image/png", png);
        assertEquals(201, result.statusCode(), string(result));
        assertEquals(node(result), node(upload(owner, pngKey, "image/png", png)));
        assertEquals(409, upload(owner, pngKey, "image/jpeg", image("jpeg")).statusCode());
        assertEquals(409, upload(owner, UUID.randomUUID(), "image/png", png).statusCode());
        var jpeg = upload(owner, UUID.randomUUID(), "image/jpeg", image("jpeg"));
        assertEquals(201, jpeg.statusCode(), string(jpeg));
        String id = node(result).get("attachmentId").asString();
        String preview = node(result).get("previewUrl").asString();
        var privateImage = get(owner, preview);
        assertEquals(200, privateImage.statusCode());
        assertEquals("image/png", privateImage.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(privateImage.body().length, privateImage.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals("private, no-store", privateImage.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", privateImage.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(401, get(null, preview).statusCode());
        assertEquals(404, get(other, preview).statusCode());
        assertEquals(404, get(null, "/api/images/sakes/1/" + id).statusCode());
        assertEquals(404, post(other, ROOT, UUID.randomUUID(), draft(id)).statusCode());

        var created = post(owner, ROOT, UUID.randomUUID(), draft(id));
        assertEquals(201, created.statusCode(), string(created));
        assertEquals(404, post(owner, ROOT, UUID.randomUUID(), draft(id)).statusCode());
        String path = ROOT + "/" + node(created).get("id").asString();
        String linked = path + "/attachments/" + id;
        assertEquals(404, get(owner, preview).statusCode());
        assertEquals(404, get(other, linked).statusCode());
        assertEquals(200, get(owner, linked).statusCode());
        assertEquals(200, post(owner, path + "/cancel", null, Map.of("expectedVersion", 0)).statusCode());
        assertEquals(404, get(owner, linked).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?", Integer.class, id));
    }

    @Test
    void invalidImagesCountAsIntentAndExpiredAttachmentsCannotBeClaimed() throws Exception {
        var owner = member("ROLE_USER");
        assertEquals(415, upload(owner, UUID.randomUUID(), "image/svg+xml", new byte[]{1}).statusCode());
        assertEquals(413, upload(owner, UUID.randomUUID(), "image/png", new byte[SubmissionLimits.FILE_BYTES + 1]).statusCode());
        assertEquals(422, upload(owner, UUID.randomUUID(), "image/jpeg", image("png")).statusCode());
        assertEquals(422, upload(owner, UUID.randomUUID(), "image/png", pixelBomb()).statusCode());
        assertEquals(429, upload(owner, UUID.randomUUID(), "image/png", image("png")).statusCode());

        var fresh = member("ROLE_USER");
        var valid = upload(fresh, UUID.randomUUID(), "image/png", image("png"));
        assertEquals(201, valid.statusCode(), string(valid));
        String id = node(valid).get("attachmentId").asString();
        db.update("UPDATE SUBMISSION_ATTACHMENT SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        assertEquals(404, get(fresh, node(valid).get("previewUrl").asString()).statusCode());
        assertEquals(404, post(fresh, ROOT, UUID.randomUUID(), draft(id)).statusCode());
    }

    @Test
    void putAndDatabaseFailureRecoverWithSameRequestAndQuarantineBlocksClaim() throws Exception {
        var owner = member("ROLE_USER");
        var key = UUID.randomUUID();
        byte[] png = image("png");
        staging.writesFail = true;
        try {
            assertEquals(503, upload(owner, key, "image/png", png).statusCode());
        } finally { staging.writesFail = false; }
        String id = db.queryForObject("SELECT id FROM SUBMISSION_ATTACHMENT WHERE request_key=?", String.class, key.toString());
        assertEquals(404, post(owner, ROOT, UUID.randomUUID(), draft(id)).statusCode());
        db.execute("ALTER TABLE SUBMISSION_ATTACHMENT ADD CONSTRAINT ugc_fixture_fail CHECK (id <> '" + id + "' OR status <> 'READY')");
        try {
            assertEquals(503, upload(owner, key, "image/png", png).statusCode());
        } finally { db.execute("ALTER TABLE SUBMISSION_ATTACHMENT DROP CHECK ugc_fixture_fail"); }
        assertEquals("RETRYABLE_FAILURE", db.queryForObject("SELECT status FROM SUBMISSION_ATTACHMENT WHERE id=?", String.class, id));
        var retried = upload(owner, key, "image/png", png);
        assertEquals(201, retried.statusCode(), string(retried));
        assertEquals(id, node(retried).get("attachmentId").asString());
        String objectKey = db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE asset_id=?", String.class, id);
        assertEquals(1, staging.writes.get(objectKey));
        var stored = staging.objects.get(objectKey);
        staging.objects.put(objectKey, new ImageStorage.Payload(stored.contentType(), stored.cacheControl(),
                "0".repeat(64), stored.bytes()));
        String preview = node(retried).get("previewUrl").asString();
        db.execute("ALTER TABLE IMAGE_OBJECT ADD CONSTRAINT ugc_read_failure CHECK (asset_id <> '" + id
                + "' OR storage_state <> 'PERMANENT_FAILURE')");
        try {
            assertEquals(503, get(owner, preview).statusCode());
            assertEquals("VERIFIED", db.queryForObject("SELECT storage_state FROM IMAGE_OBJECT WHERE asset_id=?", String.class, id));
        } finally { db.execute("ALTER TABLE IMAGE_OBJECT DROP CHECK ugc_read_failure"); }
        assertEquals(503, get(owner, preview).statusCode());
        assertEquals(404, post(owner, ROOT, UUID.randomUUID(), draft(id)).statusCode());
        assertEquals("PERMANENT_FAILURE", db.queryForObject("SELECT storage_state FROM IMAGE_OBJECT WHERE asset_id=?", String.class, id));
    }

    @Test
    void concurrentMemberLimitsAndVersionChecksRemainAtomic() throws Exception {
        var owner = member("ROLE_USER");
        var start = new CountDownLatch(1);
        var statuses = new ArrayList<Future<Integer>>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return post(owner, ROOT, UUID.randomUUID(), draft(null)).statusCode();
                }));
            }
            start.countDown();
            var codes = new ArrayList<Integer>();
            for (var future : statuses) codes.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(2, Collections.frequency(codes, 201));
            assertEquals(3, Collections.frequency(codes, 429));
        }
        String id = db.queryForObject("SELECT MIN(id) FROM CATALOG_SUBMISSION WHERE owner_no=?", String.class, owner.no());
        db.update("UPDATE CATALOG_SUBMISSION SET status='NEEDS_REVISION' WHERE id=?", id);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var revision = pool.submit(() -> post(owner, ROOT + "/" + id + "/revisions", null,
                    Map.of("expectedVersion", 0, "data", draft(null))).statusCode());
            var cancel = pool.submit(() -> post(owner, ROOT + "/" + id + "/cancel", null, Map.of("expectedVersion", 0)).statusCode());
            var codes = List.of(revision.get(15, TimeUnit.SECONDS), cancel.get(15, TimeUnit.SECONDS));
            assertTrue(codes.contains(409));
            assertTrue(codes.contains(200) || codes.contains(201));
        }
    }

    @Test
    void secondServiceInstanceCannotDuplicateActiveUpload() throws Exception {
        var owner = member("ROLE_USER");
        byte[] png = image("png");
        UUID key = UUID.randomUUID();
        assertEquals(422, upload(owner, UUID.randomUUID(), "image/png", new byte[]{1}).statusCode());
        assertEquals(0, db.queryForObject("SELECT reserved_bytes FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",
                Integer.class, owner.no()));
        // A different JVM admits independently, then its image storage becomes unavailable after the DB claim.
        var otherImages = org.mockito.Mockito.mock(com.sakezuki.backend.image.ImageAssetService.class);
        var otherPermit = org.mockito.Mockito.mock(com.sakezuki.backend.image.ImageAssetService.Processing.class);
        org.mockito.Mockito.when(otherImages.admit()).thenReturn(otherPermit);
        org.mockito.Mockito.doThrow(new IOException("fixture storage unavailable")).when(otherPermit)
                .stage(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var another = new AttachmentService(attachments, submissions, limits, otherImages, stores, manager);
        var started = new CountDownLatch(1);
        var continueUpload = new CountDownLatch(1);
        staging.beforeWrite = () -> {
            started.countDown();
            try { if (!continueUpload.await(10, TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> upload(owner, key, "image/png", png));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertEquals(409, assertThrows(ResponseStatusException.class,
                        () -> another.upload(owner.no(), key, "image/png", png)).getStatusCode().value());
                assertEquals(429, assertThrows(ResponseStatusException.class,
                        () -> another.upload(owner.no(), UUID.randomUUID(), "image/png", png)).getStatusCode().value());
                db.update("UPDATE SUBMISSION_ATTACHMENT SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE request_key=?",
                        key.toString());
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> another.upload(owner.no(), key, "image/png", png)).getStatusCode().value());
            } finally { continueUpload.countDown(); }
            assertEquals(409, first.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(201, upload(owner, key, "image/png", png).statusCode());
        } finally { staging.beforeWrite = () -> {}; }
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?", Integer.class, owner.no()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=? AND status='READY'",
                Integer.class, owner.no()));
    }

    @Test
    void previewChecksAccountAndRightsAgainAfterReadingStorage() throws Exception {
        var owner = member("ROLE_USER");
        var uploaded = upload(owner, UUID.randomUUID(), "image/png", image("png"));
        assertEquals(201, uploaded.statusCode(), string(uploaded));
        String id = node(uploaded).get("attachmentId").asString();
        String preview = node(uploaded).get("previewUrl").asString();
        staging.afterRead = () -> db.update("UPDATE MEMBER SET status='INACTIVE' WHERE no=?", owner.no());
        try {
            var response = get(owner, preview);
            assertEquals(404, response.statusCode());
            assertFalse(response.headers().firstValue("Content-Type").orElse("").startsWith("image/"));
        } finally { staging.afterRead = () -> {}; }
        db.update("UPDATE MEMBER SET status='ACTIVE' WHERE no=?", owner.no());
        staging.afterRead = () -> db.update("""
                INSERT INTO IMAGE_RIGHTS_EVENT(asset_id,decision,scope,evidence_ref,decided_by)
                VALUES (?,'REVOKED','PUBLIC_WEB','fixture','fixture')
                """, id);
        try {
            assertEquals(404, get(owner, preview).statusCode());
        } finally { staging.afterRead = () -> {}; }
        assertEquals(404, post(owner, ROOT, UUID.randomUUID(), draft(id)).statusCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentHttpUploadIsRejectedBeforeStorageOrQuota(boolean sameMember) throws Exception {
        var owner = member("ROLE_USER");
        var contender = sameMember ? owner : member("ROLE_USER");
        var firstKey = UUID.randomUUID();
        var secondKey = UUID.randomUUID();
        byte[] png = image("png");
        byte[] jpeg = image("jpeg");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        staging.beforeWrite = () -> {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> upload(owner, firstKey, "image/png", png));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                var before = uploadUsage(contender);
                int reads = staging.readRequests.get();
                int writes = staging.writeRequests.get();
                var blocked = upload(contender, secondKey, "image/jpeg", jpeg);
                assertEquals(503, blocked.statusCode());
                assertEquals("private, no-store", blocked.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("nosniff", blocked.headers().firstValue("X-Content-Type-Options").orElseThrow());
                assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE request_key=?",
                        Integer.class, secondKey.toString()));
                assertEquals(before, uploadUsage(contender));
                assertEquals(reads, staging.readRequests.get());
                assertEquals(writes, staging.writeRequests.get());
            } finally {
                release.countDown();
            }
            assertEquals(201, first.get(15, TimeUnit.SECONDS).statusCode());
            assertEquals(201, upload(contender, secondKey, "image/jpeg", jpeg).statusCode());
        } finally {
            staging.beforeWrite = () -> {};
        }
    }

    @Test
    void multipartAndValidationErrorsReleaseHttpUploadPermit() throws Exception {
        var owner = member("ROLE_USER");
        var malformed = request(owner, ROOT + "/uploads")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "multipart/form-data")
                .POST(HttpRequest.BodyPublishers.ofString("no multipart boundary")).build();
        assertEquals(422, http.send(malformed, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(422, upload(owner, UUID.randomUUID(), "image/png", new byte[]{1}).statusCode());
        var key = UUID.randomUUID();
        byte[] png = image("png");
        staging.writesFail = true;
        try {
            assertEquals(503, upload(owner, key, "image/png", png).statusCode());
        } finally {
            staging.writesFail = false;
        }
        assertEquals(201, upload(owner, key, "image/png", png).statusCode());
    }

    @Test
    void duplicateKeysAreRejectedByEachJsonEndpointWithoutPersistedChanges() throws Exception {
        var owner = member("ROLE_USER");
        var key = UUID.randomUUID();
        var original = draft(null);
        var receipt = post(owner, ROOT, key, original);
        assertEquals(201, receipt.statusCode(), string(receipt));
        String id = node(receipt).get("id").asString();
        String path = ROOT + "/" + id;
        db.update("UPDATE CATALOG_SUBMISSION SET status='NEEDS_REVISION',version=1 WHERE id=?", id);
        var uploaded = upload(owner, UUID.randomUUID(), "image/png", image("png"));
        assertEquals(201, uploaded.statusCode(), string(uploaded));
        String asset = node(uploaded).get("attachmentId").asString();
        String payload = json.writeValueAsString(draft(asset));
        var before = node(get(owner, path));
        var usage = uploadUsage(owner);
        for (String duplicate : List.of(
                payload.replace("\"ja\":\"合成酒\"", "\"ja\":\"First\",\"ja\":\"Second\""),
                payload.replace("\"existingNo\":1", "\"existingNo\":1,\"existingNo\":1"),
                payload.replace("\"attachmentId\":", "\"attachmentId\":null,\"attachmentId\":"),
                payload.replace("\"imageConsent\":true", "\"imageConsent\":true,\"imageConsent\":true"))) {
            assertNotEquals(payload, duplicate);
            assertEquals(422, postJson(owner, ROOT, UUID.randomUUID(), duplicate, false).statusCode());
        }
        assertEquals(422, postJson(owner, path + "/revisions", null,
                "{\"expectedVersion\":1,\"expectedVersion\":1,\"data\":" + payload + "}", false).statusCode());
        assertEquals(422, postJson(owner, path + "/revisions", null,
                "{\"expectedVersion\":1,\"data\":"
                        + payload.replace("\"existingNo\":1", "\"existingNo\":2,\"existingNo\":1") + "}", false).statusCode());
        assertEquals(422, postJson(owner, path + "/cancel", null,
                "{\"expectedVersion\":1,\"reason\":\"same\",\"reason\":\"same\"}", false).statusCode());
        assertEquals(before, node(get(owner, path)));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM CATALOG_SUBMISSION WHERE owner_no=?", Integer.class, owner.no()));
        assertNull(db.queryForObject("SELECT submission_id FROM SUBMISSION_ATTACHMENT WHERE id=?", String.class, asset));
        assertEquals(usage, uploadUsage(owner));
        assertEquals(node(receipt), node(post(owner, ROOT, key, original)));
    }

    @Test
    void numbersAndConsentRequireNativeJsonTypes() throws Exception {
        var owner = member("ROLE_USER");
        var uploaded = upload(owner, UUID.randomUUID(), "image/png", image("png"));
        assertEquals(201, uploaded.statusCode(), string(uploaded));
        var data = draft(node(uploaded).get("attachmentId").asString());
        data.put("capacityMl", 720);
        data.put("acidity", Map.of("min", 1, "max", 2.75));
        String payload = json.writeValueAsString(data);
        UUID key = UUID.randomUUID();
        for (var replacement : Map.of(
                "\"existingNo\":1", "\"existingNo\":\"1\"",
                "\"capacityMl\":720", "\"capacityMl\":\"720\"",
                "\"min\":-12.5", "\"min\":\"-12.5\"",
                "\"max\":2.75", "\"max\":\"2.75\"",
                "\"imageConsent\":true", "\"imageConsent\":\"true\"").entrySet()) {
            assertTrue(payload.contains(replacement.getKey()));
            assertEquals(422, postJson(owner, ROOT, key,
                    payload.replace(replacement.getKey(), replacement.getValue()), false).statusCode());
        }
        assertEquals(422, postJson(owner, ROOT, key,
                payload.replace("\"imageConsent\":true", "\"imageConsent\":1"), false).statusCode());
        assertEquals(422, postJson(owner, ROOT, key,
                payload.replace("\"capacityMl\":720", "\"capacityMl\":720.0"), false).statusCode());
        assertEquals(422, postJson(owner, ROOT, key, payload + " {}", false).statusCode());
        var accepted = postJson(owner, ROOT, key, payload, false);
        assertEquals(201, accepted.statusCode(), string(accepted));
        String path = ROOT + "/" + node(accepted).get("id").asString();
        assertEquals(422, postJson(owner, path + "/cancel", null, "{\"expectedVersion\":\"0\"}", false).statusCode());
        assertEquals(422, postJson(owner, path + "/revisions", null,
                "{\"expectedVersion\":\"0\",\"data\":" + payload + "}", false).statusCode());
        assertEquals(node(accepted), node(postJson(owner, ROOT, key, payload, false)));
        assertEquals(200, postJson(owner, path + "/cancel", null, "{\"expectedVersion\":0}", false).statusCode());
    }

    @Test
    void descriptionsNormalizeCrLfAndDoNotGrantImageRights() throws Exception {
        var owner = member("ROLE_USER");
        var uploaded = upload(owner, UUID.randomUUID(), "image/png", image("png"));
        assertEquals(201, uploaded.statusCode(), string(uploaded));
        String asset = node(uploaded).get("attachmentId").asString();
        var data = draft(asset);
        for (String invalid : List.of(" \n\r\n", "a\tb", "a\rb", "a\u0000b", "x".repeat(2001))) {
            data.put("notes", invalid);
            assertInvalid(owner, data);
        }
        data.put("notes", "<script>fixture()</script>\r\n둘째 줄");
        data.put("imageRights", "합성 이미지\r\n제보용");
        data.put("sake", Map.of("ja", "合成\n酒", "ko", "합성 사케"));
        assertInvalid(owner, data);
        data.put("sake", draft(null).get("sake"));
        var key = UUID.randomUUID();
        var created = post(owner, ROOT, key, data);
        assertEquals(201, created.statusCode(), string(created));
        String id = node(created).get("id").asString();
        var persisted = json.readTree(db.queryForObject(
                "SELECT payload FROM SUBMISSION_REVISION WHERE submission_id=? AND revision_no=1", String.class, id));
        var returned = node(get(owner, ROOT + "/" + id)).get("revisions").get(0).get("data");
        assertEquals(persisted, returned);
        assertEquals("<script>fixture()</script>\n둘째 줄", persisted.get("notes").asString());
        assertEquals("합성 이미지\n제보용", persisted.get("imageRights").asString());
        data.put("notes", persisted.get("notes").asString());
        data.put("imageRights", persisted.get("imageRights").asString());
        assertEquals(node(created), node(post(owner, ROOT, key, data)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?", Integer.class, asset));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestSizeBoundariesApplyWithLengthAndChunkedBodies(boolean chunked) throws Exception {
        var owner = member("ROLE_USER");
        String payload = json.writeValueAsString(draft(null));
        String exact = payload + " ".repeat(SubmissionLimits.JSON_BYTES - payload.getBytes(StandardCharsets.UTF_8).length);
        var key = UUID.randomUUID();
        assertEquals(201, postJson(owner, ROOT, key, exact, chunked).statusCode());
        assertEquals(413, postJson(owner, ROOT, key, exact + " ", chunked).statusCode());

        String boundary = "ugc-limit-fixture";
        byte[] file = new byte[SubmissionLimits.FILE_BYTES];
        String first = part(boundary, "file", "image/png");
        String second = part(boundary, "extra", "image/png");
        String end = "\r\n--" + boundary + "--\r\n";
        assertEquals(422, uploadBody(owner, UUID.randomUUID(), boundary, HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(first), HttpRequest.BodyPublishers.ofByteArray(file),
                HttpRequest.BodyPublishers.ofString(end)), chunked).statusCode(),
                "10 MiB reaches raster validation");
        assertEquals(413, uploadBody(owner, UUID.randomUUID(), boundary, HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(first), HttpRequest.BodyPublishers.ofByteArray(file),
                HttpRequest.BodyPublishers.ofString("a" + end)), chunked).statusCode());

        String between = "\r\n" + second;
        int padding = SubmissionLimits.MULTIPART_BYTES - file.length - first.length() - between.length() - end.length();
        byte[] extra = new byte[padding];
        HttpRequest.BodyPublisher exactMultipart = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(first), HttpRequest.BodyPublishers.ofByteArray(file),
                HttpRequest.BodyPublishers.ofString(between), HttpRequest.BodyPublishers.ofByteArray(extra),
                HttpRequest.BodyPublishers.ofString(end));
        assertEquals(SubmissionLimits.MULTIPART_BYTES, exactMultipart.contentLength());
        assertEquals(422, uploadBody(owner, UUID.randomUUID(), boundary, exactMultipart, chunked).statusCode(),
                "11 MiB reaches multipart field validation");
        HttpRequest.BodyPublisher oversized = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(first), HttpRequest.BodyPublishers.ofByteArray(file),
                HttpRequest.BodyPublishers.ofString(between), HttpRequest.BodyPublishers.ofByteArray(extra),
                HttpRequest.BodyPublishers.ofString("a" + end));
        assertEquals(413, uploadBody(owner, UUID.randomUUID(), boundary, oversized, chunked).statusCode());
        assertEquals(201, upload(owner, UUID.randomUUID(), "image/jpeg", image("jpeg")).statusCode());
    }

    private Map<String, Object> uploadUsage(Member member) {
        return db.queryForMap("""
                SELECT COUNT(*) AS intents,
                    COALESCE(SUM(created_at > UTC_TIMESTAMP(6) - INTERVAL 1 DAY), 0) AS daily,
                    COALESCE(SUM(reserved_bytes), 0) AS reserved
                FROM SUBMISSION_ATTACHMENT WHERE owner_no=?
                """, member.no());
    }

    @Test
    void administratorReadsAreAuthorizedByCurrentDatabaseRoleAndDoNotExposeAccountDetails() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String id = submit(owner, draft(null));
        String path = adminPath(id);
        assertEquals(401, get(null, ADMIN).statusCode());
        assertEquals(403, get(owner, path).statusCode());
        assertEquals(404, get(admin, adminPath(UUID.randomUUID().toString())).statusCode());
        var detail = get(admin, path);
        assertEquals(200, detail.statusCode(), string(detail));
        assertEquals("private, no-store", detail.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(owner.no(), node(detail).get("submission").get("owner").longValue());
        assertEquals("MEMBER", node(detail).get("original").get("authorRole").asString());
        assertEquals(1, node(detail).get("current").get("number").intValue());
        assertFalse(string(detail).contains("example.invalid"));
        assertFalse(string(detail).contains("\"password\""));
        assertFalse(string(detail).contains("\"phone\""));
        assertFalse(string(detail).contains("\"address1\""));
        String search = java.net.URLEncoder.encode("合成酒", StandardCharsets.UTF_8);
        assertEquals(200, get(admin, ADMIN+"?status=PENDING&sort=CREATED&q="+search+"&size=1").statusCode());
        assertEquals(422, get(admin, ADMIN+"?size=51").statusCode());
        assertEquals(422, get(admin, ADMIN+"?status=BAD").statusCode());
        assertEquals(422, post(admin,path+"/decisions",UUID.randomUUID(),Map.of(
                "actorNo",owner.no(),"expectedVersion",0,"revisionNo",1,"decision","REJECT","reason","시험")).statusCode());
        db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=? AND authority='ROLE_ADMIN'", admin.no());
        assertEquals(403, get(admin, path).statusCode());
        assertEquals(403, postJson(admin,path+"/decisions",UUID.randomUUID(),"{",false).statusCode());
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?,'ROLE_ADMIN')",admin.no());
        db.update("UPDATE MEMBER SET status='INACTIVE' WHERE no=?",admin.no());
        assertEquals(401, get(admin,path).statusCode());
    }

    @Test
    void adminQueueFiltersStatusNameAndStablePagesWithoutRevisionPayloads() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        var firstDraft = draft(null);
        var secondDraft = draft(null);
        String marker = "검수" + UUID.randomUUID();
        firstDraft.put("sake",Map.of("ja",marker+" 1","ko","합성"));
        secondDraft.put("sake",Map.of("ja",marker+" 2","ko","합성"));
        String first = submit(owner,firstDraft);
        String second = submit(owner,secondDraft);
        db.update("UPDATE CATALOG_SUBMISSION SET created_at='2025-01-01 00:00:00',updated_at='2025-01-01 00:00:00' WHERE id IN (?,?)",first,second);
        String url = ADMIN + "?status=PENDING&sort=UPDATED&size=1&q=" + java.net.URLEncoder.encode(marker,StandardCharsets.UTF_8);
        var front = node(get(admin,url));
        var back = node(get(admin,url+"&page=1"));
        assertTrue(front.get("hasMore").booleanValue());
        assertFalse(back.get("hasMore").booleanValue());
        assertEquals(List.of(first,second).stream().sorted(Comparator.reverseOrder()).toList(),
                List.of(front.get("items").get(0).get("id").asString(),back.get("items").get(0).get("id").asString()));
        assertFalse(front.get("items").get(0).has("payload"));
        assertFalse(front.get("items").get(0).has("attachment"));
        assertEquals(0, node(get(admin,url.replace("PENDING","REJECTED"))).get("items").size());
    }

    @Test
    void administratorRevisionPreservesOriginalAndCannotInventConsentOrMoveAttachments() throws Exception {
        var owner = member("ROLE_USER");
        var other = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String asset = uploadedAsset(owner);
        String foreign = uploadedAsset(other);
        var original = draft(asset);
        original.put("imageRights","첫째 줄\n둘째 줄");
        String id = submit(owner, original);
        submit(other,draft(foreign));
        var changed = new LinkedHashMap<>(original);
        changed.put("notes","수정\r\n설명");
        changed.put("imageRights","다른 근거");
        assertEquals(422, reviseAsAdmin(admin,id,0,changed).statusCode());
        changed.put("imageRights","첫째 줄\n둘째 줄");
        changed.put("imageConsent",false);
        assertEquals(422, reviseAsAdmin(admin,id,0,changed).statusCode());
        changed.put("imageConsent",true);
        changed.put("attachmentId",foreign);
        assertEquals(422, reviseAsAdmin(admin,id,0,changed).statusCode());
        changed.put("attachmentId",asset);
        assertEquals(422, postJson(admin,adminPath(id)+"/revisions",null,
                "{\"expectedVersion\":0,\"expectedVersion\":0,\"reason\":\"검수\",\"data\":"
                + json.writeValueAsString(changed)+"}",false).statusCode());
        changed.put("capacityMl", "720");
        assertEquals(422, reviseAsAdmin(admin,id,0,changed).statusCode());
        changed.remove("capacityMl");
        assertEquals(409, reviseAsAdmin(admin,id,1,changed).statusCode());
        assertEquals(201, reviseAsAdmin(admin,id,0,changed).statusCode());
        var detail = node(get(admin,adminPath(id)));
        assertEquals(2,detail.get("current").get("number").intValue());
        assertEquals(1,detail.get("current").get("parent").intValue());
        assertEquals("ADMIN",detail.get("current").get("authorRole").asString());
        assertEquals(admin.no(),detail.get("current").get("authorNo").longValue());
        assertEquals("수정\n설명",detail.get("current").get("data").get("notes").asString());
        assertEquals("검수 수정",detail.get("events").get(1).get("reason").asString());
        assertEquals("VERIFIED",detail.get("current").get("attachment").get("stagingState").asString());
        assertTrue(detail.get("original").get("data").get("notes").isNull());
        var without = draft(null);
        assertEquals(201,reviseAsAdmin(admin,id,1,without).statusCode());
        assertEquals(1,node(get(admin,adminPath(id))).get("previous").size());
        assertEquals(422,reviseAsAdmin(admin,id,2,original).statusCode());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?",Integer.class,asset));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE name_ja='合成酒'",Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM CREDIT_LEDGER WHERE member_no IN (?,?)",Integer.class,owner.no(),admin.no()));
    }

    @Test
    void adminDecisionsReplayAndDoNotCarryReviewAcrossUserRevisions() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String id = submit(owner,draft(null));
        UUID key = UUID.randomUUID();
        assertEquals(422, decideAsAdmin(admin,id,key,"APPROVE",0,1).statusCode());
        assertEquals(422, decideAsAdmin(admin,id,key,0,0,1).statusCode());
        assertEquals(409, decideAsAdmin(admin,id,key,"REJECT",0,2).statusCode());
        var change = decideAsAdmin(admin,id,key,"REQUEST_REVISION",0,1);
        assertEquals(200, change.statusCode(), string(change));
        assertEquals("NEEDS_REVISION",node(change).get("status").asString());
        assertEquals(409,reviseAsAdmin(admin,id,1,draft(null)).statusCode());
        assertEquals(201,post(owner,ROOT+"/"+id+"/revisions",null,
                Map.of("expectedVersion",1,"data",draft(null))).statusCode());
        assertEquals(node(change),node(decideAsAdmin(admin,id,key,"REQUEST_REVISION",0,1)));
        assertEquals(409,decideAsAdmin(admin,id,key,"REJECT",2,2).statusCode());
        assertEquals(409,decideAsAdmin(admin,id,UUID.randomUUID(),"REJECT",2,1).statusCode());
        assertEquals(200,decideAsAdmin(admin,id,UUID.randomUUID(),"REJECT",2,2).statusCode());
        assertEquals(409,decideAsAdmin(admin,id,UUID.randomUUID(),"REJECT",3,2).statusCode());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_REVIEW_DECISION WHERE submission_id=?",Integer.class,id));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=?",Integer.class,id));
    }

    @Test
    void decisionLedgerWriteFailureRollsBackStateAndAudit() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String id = submit(owner,draft(null));
        UUID key = UUID.randomUUID();
        db.execute("ALTER TABLE SUBMISSION_REVIEW_DECISION ADD CONSTRAINT fixture_review_failure CHECK (request_key <> '"+key+"')");
        try {
            var failed = decideAsAdmin(admin,id,key,"REJECT",0,1);
            assertEquals(500,failed.statusCode());
            assertFalse(string(failed).contains("fixture_review_failure"));
        } finally {
            db.execute("ALTER TABLE SUBMISSION_REVIEW_DECISION DROP CHECK fixture_review_failure");
        }
        assertEquals("PENDING",db.queryForObject("SELECT status FROM CATALOG_SUBMISSION WHERE id=?",String.class,id));
        assertEquals(0L,db.queryForObject("SELECT version FROM CATALOG_SUBMISSION WHERE id=?",Long.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=?",Integer.class,id));
        assertEquals(200,decideAsAdmin(admin,id,key,"REJECT",0,1).statusCode());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "REVISION,CANCEL", "REJECT,CANCEL", "REQUEST_REVISION,CANCEL",
            "REJECT,REQUEST_REVISION", "REVISION,REJECT", "REJECT,RESUBMIT"
    })
    void reviewStateRacesCommitExactlyOneOperation(String firstAction, String secondAction) throws Exception {
        var owner = member("ROLE_USER");
        var first = member("ROLE_ADMIN");
        var second = member("ROLE_ADMIN");
        String id = submit(owner,draft(null));
        if ("RESUBMIT".equals(secondAction)) {
            assertEquals(200,decideAsAdmin(first,id,UUID.randomUUID(),"REQUEST_REVISION",0,1).statusCode());
        }
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> {
                gate.await();
                if ("RESUBMIT".equals(secondAction)) return decideAsAdmin(first,id,UUID.randomUUID(),"REJECT",1,1).statusCode();
                return raceAction(first,id,firstAction);
            });
            var b = pool.submit(() -> {
                gate.await();
                if ("RESUBMIT".equals(secondAction)) return post(owner,ROOT+"/"+id+"/revisions",null,
                        Map.of("expectedVersion",1,"data",draft(null))).statusCode();
                return raceAction("CANCEL".equals(secondAction)?owner:second,id,secondAction);
            });
            gate.countDown();
            var codes = List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(codes,409),codes.toString());
            assertTrue(codes.contains(200)||codes.contains(201),codes.toString());
        }
        int expected = "RESUBMIT".equals(secondAction)?2:1;
        assertEquals((long)expected,db.queryForObject("SELECT version FROM CATALOG_SUBMISSION WHERE id=?",Long.class,id));
        assertEquals(expected+1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=?",Integer.class,id));
    }

    @Test
    void roleRevocationCommittedWhileDecisionWaitsPreventsAuditOrStateChange() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String id = submit(owner,draft(null));
        var revoked = new CountDownLatch(1);
        var finishRevocation = new CountDownLatch(1);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var revoke = pool.submit(() -> transaction.executeWithoutResult(status -> {
                db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=? AND authority='ROLE_ADMIN'",admin.no());
                revoked.countDown();
                await(finishRevocation);
            }));
            assertTrue(revoked.await(10,TimeUnit.SECONDS));
            var decision = pool.submit(() -> decideAsAdmin(admin,id,UUID.randomUUID(),"REJECT",0,1));
            try {
                boolean waiting = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!waiting && System.nanoTime() < deadline && !decision.isDone()) {
                    waiting = db.queryForObject("""
                            SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()
                            AND INFO LIKE '%MEMBER_AUTHORITY%' AND INFO LIKE '%FOR SHARE%'
                            """,Integer.class) > 0;
                    if (!waiting) Thread.sleep(20);
                }
                assertTrue(waiting, "live decision did not wait for the role row");
            } finally {
                finishRevocation.countDown();
            }
            revoke.get(10,TimeUnit.SECONDS);
            assertEquals(403,decision.get(10,TimeUnit.SECONDS).statusCode());
        }
        assertEquals("PENDING",db.queryForObject("SELECT status FROM CATALOG_SUBMISSION WHERE id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=?",Integer.class,id));
    }

    @Test
    void adminPreviewRechecksAuthorityAccountRightsAndIntegrityAfterRead() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String asset = uploadedAsset(owner);
        String id = submit(owner,draft(asset));
        String other = submit(owner,draft(null));
        String path = adminPath(id)+"/attachments/"+asset;
        var ok = get(admin,path);
        assertEquals(200,ok.statusCode(),string(ok));
        assertEquals("image/png",ok.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(ok.body().length,ok.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals("nosniff",ok.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(403,get(owner,path).statusCode());
        assertEquals(404,get(admin,adminPath(other)+"/attachments/"+asset).statusCode());
        assertEquals(404,get(admin,adminPath(id)+"/attachments/invalid").statusCode());
        staging.afterRead = () -> db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=? AND authority='ROLE_ADMIN'",admin.no());
        try { assertImageDenied(403,get(admin,path)); }
        finally { staging.afterRead = () -> {}; }
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?,'ROLE_ADMIN')",admin.no());
        staging.afterRead = () -> db.update("UPDATE MEMBER SET status='INACTIVE' WHERE no=?",admin.no());
        try { assertImageDenied(401,get(admin,path)); }
        finally { staging.afterRead = () -> {}; }
        db.update("UPDATE MEMBER SET status='ACTIVE' WHERE no=?",admin.no());
        staging.afterRead = () -> { throw new IOException("fixture service unavailable"); };
        try { assertImageDenied(503,get(admin,path)); }
        finally { staging.afterRead = () -> {}; }
        String key = db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE asset_id=?",String.class,asset);
        var original = staging.objects.get(key);
        staging.objects.put(key,new ImageStorage.Payload(original.contentType(),original.cacheControl(),original.declaredSha256()+"invalid",original.bytes()));
        assertImageDenied(503,get(admin,path));
        staging.objects.put(key,original);
        images.revalidate(UUID.fromString(asset),ImageStores.Area.STAGING);
        byte[] corrupt = original.bytes().clone();
        corrupt[0] ^= 1;
        staging.objects.put(key,new ImageStorage.Payload(original.contentType(),original.cacheControl(),original.declaredSha256(),corrupt));
        assertImageDenied(503,get(admin,path));
        staging.objects.put(key,original);
        images.revalidate(UUID.fromString(asset),ImageStores.Area.STAGING);
        staging.afterRead = () -> db.update("""
                INSERT INTO IMAGE_RIGHTS_EVENT(asset_id,decision,scope,evidence_ref,decided_by)
                VALUES (?,'REVOKED','PUBLIC_WEB','fixture','fixture')
                """,asset);
        try { assertImageDenied(404,get(admin,path)); }
        finally { staging.afterRead = () -> {}; }
        assertEquals(404,get(admin,path).statusCode());
    }

    @Test
    void ownerAndAdminSharePrivatePreviewLimitAndReleaseIt() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String asset = uploadedAsset(owner);
        String id = submit(owner,draft(asset));
        String adminPreview = adminPath(id)+"/attachments/"+asset;
        String ownerPreview = ROOT+"/"+id+"/attachments/"+asset;
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        staging.afterRead = () -> { started.countDown(); await(release); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> get(admin,adminPreview));
            var b = pool.submit(() -> get(owner,ownerPreview));
            try {
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertImageDenied(503,get(admin,adminPreview));
            } finally {
                release.countDown();
            }
            assertEquals(200,a.get(10,TimeUnit.SECONDS).statusCode());
            assertEquals(200,b.get(10,TimeUnit.SECONDS).statusCode());
        } finally {
            staging.afterRead = () -> {};
        }
        assertEquals(200,get(admin,adminPreview).statusCode());
        assertEquals(200,decideAsAdmin(admin,id,UUID.randomUUID(),"REJECT",0,1).statusCode());
        assertImageDenied(404,get(admin,adminPreview));
    }

    @Test
    void adminQueueSearchDistinguishesNullFromTextAndKeepsBinaryLikeAndFullNames() throws Exception {
        var admin = member("ROLE_ADMIN");
        String jsonNull = searchFixture("空欄", "비어 있음", null);
        String missing = searchFixture("省略", "생략", null);
        db.update("""
                UPDATE SUBMISSION_REVISION SET payload=JSON_REMOVE(payload,'$.sake.kana')
                WHERE submission_id=? AND revision_no=1
                """, missing);
        String upper = searchFixture("銘柄Null試験", "대문자", "カナ");
        String lower = searchFixture("銘柄試験", "한글null시험", "テスト");
        String kana = searchFixture("試飲", "시음", "かなnull試行");
        String literal = searchFixture("試験%_!末", "평범한 이름", "ひらがな");
        String wildcard = searchFixture("試験ABC!末", "비교 이름", "ヒラガナ");
        String ja = "始日" + "酒".repeat(496) + "終日";
        String ko = "첫한" + "술".repeat(996) + "끝한";
        String fullKana = "はじ" + "さ".repeat(496) + "おわ";
        String longNames = searchFixture(ja, ko, fullKana);
        var fixtures = List.of(jsonNull, missing, upper, lower, kana, literal, wildcard, longNames);
        db.update("""
                UPDATE CATALOG_SUBMISSION SET created_at='2025-01-01',updated_at='2025-01-01'
                WHERE id IN (?,?,?)
                """, upper, lower, kana);
        for (String query : List.of("null", "nu", "n")) {
            assertEquals(List.of(lower,kana).stream().sorted(Comparator.reverseOrder()).toList(),
                    queueFixtureIds(queue(admin, query, "status=PENDING&size=50"), fixtures),query);
        }
        for (String query : List.of("ul", "ll", "u", "l")) {
            assertEquals(List.of(upper,lower,kana).stream().sorted(Comparator.reverseOrder()).toList(),
                    queueFixtureIds(queue(admin, query, "status=PENDING&size=50"), fixtures),query);
        }
        assertEquals(List.of(upper), queueFixtureIds(queue(admin, "Null", "size=50"),fixtures));
        assertEquals(List.of(literal), queueFixtureIds(queue(admin, "%_!", "size=50"),fixtures));
        assertEquals(List.of(literal), queueFixtureIds(queue(admin, "ひらがな", "size=50"),fixtures));
        assertEquals(List.of(wildcard), queueFixtureIds(queue(admin, "ヒラガナ", "size=50"),fixtures));
        assertEquals(List.of(literal), queueFixtureIds(queue(admin, "평범한 이름", "size=50"),fixtures));
        for (String query : List.of("始日","終日","첫한","끝한","はじ","おわ")) {
            var page = queue(admin, query, "size=50");
            assertEquals(List.of(longNames), queueFixtureIds(page,fixtures),query);
            for (var item : page.path("items")) {
                if (longNames.equals(item.path("id").asString())) {
                    assertEquals(ja,item.path("nameJa").asString());
                    assertEquals(ko,item.path("nameKo").asString());
                }
            }
        }
        var front = queue(admin, "null", "status=PENDING&sort=UPDATED&size=1");
        var back = queue(admin, "null", "status=PENDING&sort=UPDATED&size=1&page=1");
        assertEquals(1,front.path("items").size());
        assertEquals(1,back.path("items").size());
        assertTrue(front.path("hasMore").booleanValue());
        assertFalse(back.path("hasMore").booleanValue());
        assertEquals(List.of(lower,kana).stream().sorted(Comparator.reverseOrder()).toList(),
                List.of(front.path("items").get(0).path("id").asString(),back.path("items").get(0).path("id").asString()));
        assertTrue(queueFixtureIds(queue(admin,"null","status=REJECTED&size=50"),fixtures).isEmpty());
    }

    private String searchFixture(String ja, String ko, String kana) throws Exception {
        var data = draft(null);
        var names = new LinkedHashMap<String,Object>();
        names.put("ja",ja);
        names.put("ko",ko);
        names.put("kana",kana);
        data.put("sake",names);
        return submit(member("ROLE_USER"),data);
    }

    private JsonNode queue(Member admin, String query, String params) throws Exception {
        var response = get(admin,ADMIN+"?"+params+"&q="+java.net.URLEncoder.encode(query,StandardCharsets.UTF_8));
        assertEquals(200,response.statusCode(),string(response));
        return node(response);
    }

    private static List<String> queueFixtureIds(JsonNode page, List<String> fixtures) {
        var ids = new java.util.ArrayList<String>();
        for (var item : page.path("items")) {
            String id = item.path("id").asString();
            if (fixtures.contains(id)) ids.add(id);
        }
        return ids;
    }

    @Test
    void duplicateSearchUsesCurrentRevisionAndQueriesBoundedSeedCandidates() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        var sample = db.queryForMap("SELECT name_ja,name_ko,brewery_no,brand_no,sake_type FROM SAKE WHERE no=1");
        var kana = db.queryForObject("""
                SELECT LEFT(name_kana,2) AS prefix FROM SAKE WHERE CHAR_LENGTH(name_kana)>2
                GROUP BY prefix HAVING COUNT(*)>=25 ORDER BY COUNT(*) DESC,prefix LIMIT 1
                """,String.class);
        String id = submit(owner,draft(null));
        var data = draft(null);
        data.put("sake",Map.of("ja",sample.get("name_ja"),"ko",sample.get("name_ko"),"kana",kana));
        data.put("brewery",Map.of("existingNo",sample.get("brewery_no")));
        data.put("brand",Map.of("existingNo",sample.get("brand_no")));
        data.put("designation",sample.get("sake_type"));
        data.put("capacityMl",720);
        data.put("variant","합성 에디션");
        assertEquals(201,reviseAsAdmin(admin,id,0,data).statusCode());
        var response = get(admin,adminPath(id)+"/duplicates");
        assertEquals(200,response.statusCode(),string(response));
        var result = node(response);
        assertEquals(2,result.get("currentRevision").intValue());
        assertEquals(20,result.get("candidates").size());
        var top = result.get("candidates").get(0);
        assertEquals(1L,top.get("catalog").get("no").longValue());
        assertTrue(top.get("matchReasons").toString().contains("EXACT_JA"));
        assertFalse(top.get("sourceIdentifiers").isEmpty());
        assertEquals(720,result.get("submittedCapacityMl").intValue());
        assertTrue(result.get("unavailableCatalogFields").toString().contains("variant"));

        String authUrl = System.getenv("AUTH_TEST_JDBC_URL");
        assertTrue(authUrl.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?"));
        var seed = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                authUrl.replace("/sakezuki_auth_it","/sakezuki_catalog_it"),
                System.getenv("AUTH_TEST_DB_USER"),System.getenv("AUTH_TEST_DB_PASSWORD"));
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(seed).target("12").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        assertEquals(10_337,new JdbcTemplate(seed).queryForObject("SELECT COUNT(*) FROM SAKE",Integer.class));
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(seed);
        SubmissionInput draft = json.readValue(json.writeValueAsString(data),SubmissionInput.class);
        var plan = named.queryForObject("EXPLAIN ANALYZE "+AdminReviewRepository.duplicatesSql(draft),
                AdminReviewRepository.duplicateParameters(draft),String.class);
        assertNotNull(plan);
        assertFalse(plan.contains("Materialize"),plan);
        assertTrue(plan.contains("Limit: 20 row(s)"),plan);
        System.out.println("Admin duplicate fixture plan (10,337 seed rows): "+plan.replace('\n',' '));
    }

    @Test
    void duplicateSearchRanksProposedBreweryAndBrandBeforeLimit() throws Exception {
        var admin = member("ROLE_ADMIN");
        var sample = db.queryForMap("""
                SELECT s.brewery_no,s.brand_no,br.name_ja AS brewery_ja,br.name_ko AS brewery_ko,
                       bd.name_ja AS brand_ja,bd.name_ko AS brand_ko
                FROM SAKE s JOIN BREWERY br ON br.no=s.brewery_no JOIN BRAND bd ON bd.no=s.brand_no
                WHERE s.no=9394
                """);
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE name_kana LIKE 'でん%'",Integer.class) > 20);
        var data = draft(null);
        data.put("sake",Map.of("ja","順位検証用合成酒","ko","순위 검증용 합성주","kana","でん"));
        data.put("brewery",Map.of("existingNo",sample.get("brewery_no")));
        var existing = duplicateFixture(admin,data);
        assertEquals(20,existing.size());
        assertTrue(candidate(existing,9394).path("matchReasons").toString().contains("\"BREWERY\""));
        assertTrue(candidate(existing,9394).path("matchReasons").toString().contains("\"PREFIX_KANA\""));

        data.put("brewery",proposed(sample.get("brewery_ja"),"다른 양조장"));
        assertEquals(existing,duplicateFixture(admin,data));
        data.put("brewery",proposed("別の蔵",sample.get("brewery_ko")));
        assertEquals(existing,duplicateFixture(admin,data));
        data.put("sake",Map.of("ja","順位検証用合成酒","ko","순위 검증용 합성주","kana","  テン  "));
        assertEquals(existing,duplicateFixture(admin,data));
        data.put("sake",Map.of("ja","順位検証用合成酒","ko","순위 검증용 합성주","kana","でん"));
        data.put("brewery",proposed(sample.get("brewery_ja")+" ","다른 양조장"));
        assertNull(candidate(duplicateFixture(admin,data),9394));
        data.put("brewery",proposed("無関係の蔵","일치하지 않는 양조장"));
        var unmatched = duplicateFixture(admin,data);
        assertEquals(20,unmatched.size());
        assertNull(candidate(unmatched,9394));
        assertTrue(unmatched.get(0).path("reviewDifferences").toString().contains("\"BREWERY\""));

        data.put("brand",proposed(sample.get("brand_ja"),"다른 브랜드"));
        var brandJa = duplicateFixture(admin,data);
        assertTrue(candidate(brandJa,9394).path("matchReasons").toString().contains("\"BRAND\""));
        data.put("brand",proposed("別銘柄",sample.get("brand_ko")));
        assertEquals(brandJa,duplicateFixture(admin,data));
        assertEquals(20,brandJa.size());

        String name = "無銘柄" + UUID.randomUUID();
        long no = catalogFixture(name,sample.get("brewery_no"));
        try {
            data.put("sake",Map.of("ja",name,"ko","브랜드 없음"));
            var brandless = candidate(duplicateFixture(admin,data),no);
            assertTrue(brandless.path("catalog").path("brandNo").isNull());
            assertTrue(brandless.path("reviewDifferences").toString().contains("\"BRAND\""));
            assertFalse(brandless.path("matchReasons").toString().contains("\"BRAND\""));
        } finally {
            db.update("DELETE FROM SAKE WHERE no=?",no);
        }
    }

    @Test
    void duplicateSearchReportsWhenOrderedExternalSourcesAreTruncated() throws Exception {
        var admin = member("ROLE_ADMIN");
        String name = "出典上限" + UUID.randomUUID();
        var data = draft(null);
        data.put("sake",Map.of("ja",name,"ko","출처 한도"));
        String id = submit(member("ROLE_USER"),data);
        var empty = duplicateResult(admin,id);
        assertTrue(empty.path("candidates").isEmpty());
        assertFalse(empty.path("sourcesTruncated").booleanValue());
        long first = catalogFixture(name,1);
        long second = catalogFixture(name,1);
        try {
            assertSourceCounts(duplicateResult(admin,id),0,0,false);
            for (int i=198;i>=0;i--) addSource(first,i);
            assertSourceCounts(duplicateResult(admin,id),199,0,false);
            addSource(second,0);
            assertSourceCounts(duplicateResult(admin,id),199,1,false);
            addSource(first,199);
            assertSourceCounts(duplicateResult(admin,id),200,0,true);
            for (int i=200;i<205;i++) addSource(first,i);
            var capped = duplicateResult(admin,id);
            assertSourceCounts(capped,200,0,true);
            assertEquals(first,capped.path("candidates").get(0).path("catalog").path("no").longValue());
            assertEquals(second,capped.path("candidates").get(1).path("catalog").path("no").longValue());
            var sources = capped.path("candidates").get(0).path("sourceIdentifiers");
            assertEquals(sourceKey(first,0),sources.get(0).path("externalKey").asString());
            assertEquals(sourceKey(first,199),sources.get(199).path("externalKey").asString());
            assertEquals("BREWERY_OFFICIAL",sources.get(0).path("system").asString());
        } finally {
            db.update("DELETE FROM SAKE_SOURCE WHERE sake_no IN (?,?)",first,second);
            db.update("DELETE FROM SAKE WHERE no IN (?,?)",first,second);
        }
    }

    private JsonNode duplicateFixture(Member admin, Map<String,Object> data) throws Exception {
        return duplicateResult(admin,submit(member("ROLE_USER"),data)).path("candidates");
    }

    private JsonNode duplicateResult(Member admin,String id) throws Exception {
        var response = get(admin,adminPath(id)+"/duplicates");
        assertEquals(200,response.statusCode(),string(response));
        return node(response);
    }

    private static JsonNode candidate(JsonNode rows,long no) {
        for (var row : rows) if (row.path("catalog").path("no").longValue()==no) return row;
        return null;
    }

    private static Map<String,Object> proposed(Object ja,Object ko) {
        return Map.of("proposed",Map.of("ja",ja,"ko",ko));
    }

    private long catalogFixture(String name,Object brewery) {
        db.update("INSERT INTO SAKE(name_ja,brewery_no) VALUES (?,?)",name,brewery);
        return db.queryForObject("SELECT MAX(no) FROM SAKE WHERE name_ja=?",Long.class,name);
    }

    private void addSource(long sake,int index) {
        db.update("INSERT INTO SAKE_SOURCE(sake_no,source_system,external_key) VALUES (?,'BREWERY_OFFICIAL',?)",
                sake,sourceKey(sake,index));
    }

    private static String sourceKey(long sake,int index) {
        return String.format(java.util.Locale.ROOT,"pr17-%d-%03d",sake,index);
    }

    private static void assertSourceCounts(JsonNode result,int first,int second,boolean truncated) {
        assertEquals(2,result.path("candidates").size());
        assertTrue(result.path("sourcesTruncated").isBoolean());
        assertEquals(truncated,result.path("sourcesTruncated").booleanValue());
        assertEquals(first,result.path("candidates").get(0).path("sourceIdentifiers").size());
        assertEquals(second,result.path("candidates").get(1).path("sourceIdentifiers").size());
    }

    @Test
    void approvesTheReviewedRevisionAndReplaysItsOriginalCatalogReceipt() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        var data = catalogDraft("承認");
        String name = ((Map<?, ?>) data.get("sake")).get("ja").toString();
        data.put("designation","合成推薦型");
        data.put("sakeMeter",Map.of("min",-1000,"max",-1.123));
        data.put("acidity",Map.of("min",2.123));
        data.put("alcoholPercent",Map.of("min",15.125));
        data.put("riceJa","合成米");
        data.put("riceKo","합성 쌀");
        data.put("yeastJa","合成酵母");
        data.put("yeastKo","합성 효모");
        data.put("capacityMl",720);
        data.put("variant","合成限定版");
        data.put("sourceUrl","https://example.invalid/catalog");
        String id = submit(owner,data);
        assertEquals(0,countCatalogName(name));
        UUID key = UUID.randomUUID();
        assertEquals(401,approve(null,id,key,0,1).statusCode());
        assertEquals(403,approve(owner,id,key,0,1).statusCode());
        assertEquals(409,approve(admin,id,key,1,1).statusCode());
        assertEquals(409,approve(admin,id,key,0,2).statusCode());
        assertEquals(422,post(admin,adminPath(id)+"/approvals",key,
                Map.of("expectedVersion",0,"revisionNo",1,"reason","검수","identityConfirmed",false)).statusCode());
        assertEquals(422,post(admin,adminPath(id)+"/approvals",key,
                Map.of("expectedVersion",0,"revisionNo",1,"reason","검수","identityConfirmed",true,"data",data)).statusCode());

        var approved = approve(admin,id,key,0,1);
        assertEquals(200,approved.statusCode(),string(approved));
        var receipt = node(approved);
        long sake = receipt.path("sakeNo").longValue();
        assertEquals("APPROVED",receipt.path("status").asString());
        assertEquals(1,receipt.path("version").intValue());
        assertEquals(1,receipt.path("currentRevision").intValue());
        assertFalse(receipt.path("approvedAt").asString().isBlank());
        assertEquals(receipt,node(approve(admin,id,key,0,1)));
        assertEquals(409,approve(admin,id,UUID.randomUUID(),0,1).statusCode());
        assertEquals(409,post(admin,adminPath(id)+"/approvals",key,
                Map.of("expectedVersion",0,"revisionNo",1,"reason","다른 사유","identityConfirmed",true)).statusCode());
        assertEquals(1,countCatalogName(name));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=? AND next_status='APPROVED' AND actor_no=?",
                Integer.class,id,admin.no()));
        assertEquals(1,db.queryForObject("""
                SELECT COUNT(*) FROM SAKE_SOURCE WHERE sake_no=? AND source_system='USER_SUBMISSION'
                  AND external_key IS NULL AND review_status='UNREVIEWED' AND canonical_url=? AND evidence_ref=?
                """,Integer.class,sake,"https://example.invalid/catalog","submission:"+id+"/revision:1"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE no=? AND source_id IS NULL AND image_url IS NULL AND brand_no IS NULL",Integer.class,sake));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SAKE_IMAGE WHERE sake_no=?",Integer.class,sake));
        assertEquals("-1000 ~ -1.123",db.queryForObject("SELECT sake_meter_value FROM SAKE WHERE no=?",String.class,sake));
        assertEquals("-1.123",db.queryForObject("SELECT CAST(sake_meter_max AS CHAR) FROM SAKE_RECOMMEND_PROFILE WHERE sake_no=?",String.class,sake));
        assertEquals("2.123",db.queryForObject("SELECT CAST(acidity_max AS CHAR) FROM SAKE_RECOMMEND_PROFILE WHERE sake_no=?",String.class,sake));
        assertNull(db.queryForObject("SELECT polishing_ratio_min FROM SAKE_RECOMMEND_PROFILE WHERE sake_no=?",java.math.BigDecimal.class,sake));

        var detail = get(null,"/api/sake/"+sake);
        assertEquals(200,detail.statusCode());
        assertEquals(name,node(detail).path("nameJa").asString());
        assertEquals(720,node(detail).path("capacityMl").intValue());
        assertEquals("合成限定版",node(detail).path("variant").asString());
        assertTrue(node(detail).path("imageUrl").isNull());
        var list = get(null,"/api/sake/list?fd="+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8));
        assertEquals(200,list.statusCode());
        assertTrue(string(list).contains(name));
        assertRecommendationForApprovedSake(sake);

        // Decision keys and approval keys are independent namespaces for the same administrator.
        String other = submit(member("ROLE_USER"),catalogDraft("比較"));
        assertEquals(409,approve(admin,other,key,0,1).statusCode());
        assertEquals(200,decideAsAdmin(admin,other,key,"REJECT",0,1).statusCode());
        db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=? AND authority='ROLE_ADMIN'",admin.no());
        assertEquals(403,approve(admin,id,key,0,1).statusCode());
    }

    @Test
    void preservesImageHistoryAndRequiresAnExplicitlyImageFreeAdminRevision() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String image = uploadedAsset(owner);
        var data = catalogDraft("画像");
        data.putAll(Map.of("attachmentId",image,"imageRights","합성 이미지","imageConsent",true));
        String id = submit(owner,data);
        assertEquals(409,approve(admin,id,UUID.randomUUID(),0,1).statusCode());
        for (String field : List.of("attachmentId","imageRights","imageConsent")) data.remove(field);
        assertEquals(201,reviseAsAdmin(admin,id,0,data).statusCode());
        assertEquals(409,approve(admin,id,UUID.randomUUID(),1,1).statusCode());
        var approved = approve(admin,id,UUID.randomUUID(),1,2);
        assertEquals(200,approved.statusCode(),string(approved));
        assertEquals(2,node(approved).path("currentRevision").intValue());
        assertEquals(image,db.queryForObject("SELECT attachment_id FROM SUBMISSION_REVISION WHERE submission_id=? AND revision_no=1",String.class,id));
        assertNull(db.queryForObject("SELECT attachment_id FROM SUBMISSION_REVISION WHERE submission_id=? AND revision_no=2",String.class,id));
    }

    @Test
    void blocksLegacyAndNormalizedProductCollisionsAndValidatesRelationOwnership() throws Exception {
        var admin = member("ROLE_ADMIN");
        var sample = db.queryForMap("SELECT brewery_no,name_ja FROM SAKE WHERE no=1");
        var duplicate = catalogDraft("既存");
        duplicate.put("sake",Map.of("ja","　"+sample.get("name_ja")+"　","ko","다른 표시명"));
        duplicate.put("brewery",Map.of("existingNo",sample.get("brewery_no")));
        duplicate.put("variant","합성 별도 변형");
        duplicate.put("capacityMl",360);
        assertEquals(409,approve(admin,submit(member("ROLE_USER"),duplicate),UUID.randomUUID(),0,1).statusCode());

        var existingBrewery = db.queryForMap("SELECT name_ja,name_ko FROM BREWERY WHERE no=1");
        var proposedBrewery = catalogDraft("蔵競合");
        proposedBrewery.put("brewery",proposed(existingBrewery.get("name_ja"),"다른 이름"));
        assertEquals(409,approve(admin,submit(member("ROLE_USER"),proposedBrewery),UUID.randomUUID(),0,1).statusCode());
        var existingBrand = db.queryForMap("SELECT no,brewery_no,name_ja FROM BRAND ORDER BY no LIMIT 1");
        var proposedBrand = catalogDraft("銘競合");
        proposedBrand.put("brewery",Map.of("existingNo",existingBrand.get("brewery_no")));
        proposedBrand.put("brand",proposed(existingBrand.get("name_ja"),"다른 이름"));
        assertEquals(409,approve(admin,submit(member("ROLE_USER"),proposedBrand),UUID.randomUUID(),0,1).statusCode());
        var wrongBrewery = db.queryForObject("SELECT no FROM BREWERY WHERE no<>? LIMIT 1",Long.class,existingBrand.get("brewery_no"));
        proposedBrand.put("brewery",Map.of("existingNo",wrongBrewery));
        String differentOwner = submit(member("ROLE_USER"),proposedBrand);
        assertEquals(200,approve(admin,differentOwner,UUID.randomUUID(),0,1).statusCode());
        proposedBrand.put("brand",Map.of("existingNo",existingBrand.get("no")));
        assertEquals(422,post(member("ROLE_USER"),ROOT,UUID.randomUUID(),proposedBrand).statusCode());
    }

    @Test
    void registersOneProductWhenTwoAdminsCompeteForOneIdentity() throws Exception {
        var firstAdmin = member("ROLE_ADMIN");
        var secondAdmin = member("ROLE_ADMIN");
        String suffix = UUID.randomUUID().toString();
        var one = catalogDraft("同一名称");
        var two = catalogDraft("同一名称");
        one.put("sake",Map.of("ja","Ａ  同一商品"+suffix,"ko","일본어 식별"));
        two.put("sake",Map.of("ja","a 同一商品"+suffix,"ko","다른 번역"));
        String first = submit(member("ROLE_USER"),one);
        String second = submit(member("ROLE_USER"),two);
        var lock = new org.springframework.transaction.support.TransactionTemplate(manager);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = lock.execute(status -> {
                db.queryForObject("SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id=1 FOR UPDATE",Integer.class);
                var a = pool.submit(() -> approve(firstAdmin,first,UUID.randomUUID(),0,1).statusCode());
                var b = pool.submit(() -> approve(secondAdmin,second,UUID.randomUUID(),0,1).statusCode());
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                String waiting = """
                        SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE()
                          AND ID<>CONNECTION_ID() AND INFO='SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id=1 FOR UPDATE'
                        """;
                while (db.queryForObject(waiting,Integer.class)<2 && System.nanoTime()<deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertEquals(2,db.queryForObject(waiting,Integer.class));
                assertFalse(a.isDone());
                assertFalse(b.isDone());
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id IN (?,?)",Integer.class,first,second));
                return List.of(a,b);
            });
            assertNotNull(results);
            assertEquals(List.of(200,409),List.of(results.get(0).get(15,TimeUnit.SECONDS),
                    results.get(1).get(15,TimeUnit.SECONDS)).stream().sorted().toList());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id IN (?,?)",Integer.class,first,second));
    }

    @Test
    void simultaneousSameRequestReplaysOneReceipt() throws Exception {
        var admin = member("ROLE_ADMIN");
        String id = submit(member("ROLE_USER"),catalogDraft("同一承認"));
        UUID key = UUID.randomUUID();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { await(start); return approve(admin,id,key,0,1); });
            var b = pool.submit(() -> { await(start); return approve(admin,id,key,0,1); });
            start.countDown();
            var first = a.get(15,TimeUnit.SECONDS);
            var second = b.get(15,TimeUnit.SECONDS);
            assertEquals(200,first.statusCode(),string(first));
            assertEquals(200,second.statusCode(),string(second));
            assertEquals(node(first),node(second));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_EVENT WHERE submission_id=? AND next_status='APPROVED'",Integer.class,id));
    }

    @ParameterizedTest
    @ValueSource(strings={"brewery","brand"})
    void simultaneousRelationProposalsRequireTheLosingSubmissionToSelectTheExistingRelation(String relation) throws Exception {
        var firstAdmin = member("ROLE_ADMIN");
        var secondAdmin = member("ROLE_ADMIN");
        String ja = "同じ関係"+UUID.randomUUID();
        String ko = "같은 관계"+UUID.randomUUID();
        var one = catalogDraft("一");
        var two = catalogDraft("二");
        one.put(relation,proposed(ja,ko));
        two.put(relation,proposed("　"+ja+"　",ko));
        String first = submit(member("ROLE_USER"),one);
        String second = submit(member("ROLE_USER"),two);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { await(start); return approve(firstAdmin,first,UUID.randomUUID(),0,1); });
            var b = pool.submit(() -> { await(start); return approve(secondAdmin,second,UUID.randomUUID(),0,1); });
            start.countDown();
            var resultA = a.get(15,TimeUnit.SECONDS);
            var resultB = b.get(15,TimeUnit.SECONDS);
            assertEquals(List.of(200,409),List.of(resultA.statusCode(),resultB.statusCode()).stream().sorted().toList());
            boolean aWon = resultA.statusCode()==200;
            String column = relation.equals("brewery") ? "brewery_no" : "brand_no";
            long sake = node(aWon ? resultA : resultB).path("sakeNo").longValue();
            long existing = db.queryForObject("SELECT "+column+" FROM SAKE WHERE no=?",Long.class,sake);
            var losingAdmin = aWon ? secondAdmin : firstAdmin;
            String losingId = aWon ? second : first;
            var corrected = aWon ? two : one;
            corrected.put(relation,Map.of("existingNo",existing));
            assertEquals(201,reviseAsAdmin(losingAdmin,losingId,0,corrected).statusCode());
            assertEquals(200,approve(losingAdmin,losingId,UUID.randomUUID(),1,2).statusCode());
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE "+column+"=?",Integer.class,existing));
        }
    }

    @Test
    void rollsBackNewRelationsProductProfileAndReceiptWhenFinalAuditFails() throws Exception {
        var admin = member("ROLE_ADMIN");
        var data = catalogDraft("戻す酒");
        String name = ((Map<?, ?>) data.get("sake")).get("ja").toString();
        String brewery = name+"蔵";
        String brand = name+"銘";
        data.put("brewery",proposed(brewery,"합성 양조장 "+UUID.randomUUID()));
        data.put("brand",proposed(brand,"합성 브랜드 "+UUID.randomUUID()));
        String id = submit(member("ROLE_USER"),data);
        UUID key = UUID.randomUUID();
        db.execute("""
                ALTER TABLE SUBMISSION_EVENT ADD CONSTRAINT fail_catalog_approval_audit
                CHECK (submission_id <> '%s' OR next_status <> 'APPROVED')
                """.formatted(id));
        try {
            assertEquals(500,approve(admin,id,key,0,1).statusCode());
        } finally {
            db.execute("ALTER TABLE SUBMISSION_EVENT DROP CHECK fail_catalog_approval_audit");
        }
        assertEquals("PENDING",db.queryForObject("SELECT status FROM CATALOG_SUBMISSION WHERE id=?",String.class,id));
        assertEquals(0,countCatalogName(name));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM BREWERY WHERE name_ja=?",Integer.class,brewery));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM BRAND WHERE name_ja=?",Integer.class,brand));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SAKE_SOURCE WHERE evidence_ref=?",Integer.class,"submission:"+id+"/revision:1"));
        var retry = approve(admin,id,key,0,1);
        assertEquals(200,retry.statusCode(),string(retry));
        long sake = node(retry).path("sakeNo").longValue();
        for (String relation : List.of("BREWERY","BRAND")) {
            assertEquals(1,db.queryForObject("""
                    SELECT COUNT(*) FROM %1$s_SOURCE x JOIN SAKE s ON s.%1$s_no=x.%1$s_no
                    WHERE s.no=? AND x.source_system='USER_SUBMISSION' AND x.external_key IS NULL AND x.canonical_url IS NULL
                    """.formatted(relation),Integer.class,sake));
        }
    }

    private void assertRecommendationForApprovedSake(long sake) {
        var condition = new com.sakezuki.backend.recommend.dto.SakeRecommendCondition();
        assertEquals(40,recommendations.getSakeRecommendCandidates(condition).size());
        condition.setSakeTypes(List.of());
        assertEquals(40,recommendations.getSakeRecommendCandidates(condition).size());
        condition.setSakeTypes(List.of("合成推薦型"));
        condition.setAcidityMin(2.123);
        condition.setAcidityMax(2.123);
        var results = recommendations.getSakeRecommendCandidates(condition);
        assertEquals(sake,results.getFirst().getNo());
        assertEquals(db.queryForObject("SELECT name_ko FROM BREWERY WHERE no=1",String.class),
                results.getFirst().getBreweryNameKo());
        String food = UUID.randomUUID().toString();
        db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES(?,?,'fixture')",food,sake);
        assertEquals(results.getFirst().getBreweryNameKo(),recommendations.getSakeRecommendList(food).getFirst().getBreweryNameKo());
    }

    private HttpResponse<byte[]> approve(Member admin,String id,UUID key,long version,int revision) throws Exception {
        return post(admin,adminPath(id)+"/approvals",key,
                Map.of("expectedVersion",version,"revisionNo",revision,"reason","검수 승인","identityConfirmed",true));
    }

    private static Map<String,Object> catalogDraft(String marker) {
        var data = draft(null);
        data.put("sake",Map.of("ja",marker+UUID.randomUUID(),"ko","합성 상품"));
        return data;
    }

    private int countCatalogName(String name) {
        return db.queryForObject("SELECT COUNT(*) FROM SAKE WHERE name_ja=?",Integer.class,name);
    }

    @org.junit.jupiter.api.AfterEach
    void clearRunnableFixtureJobs() {
        // Keep history, while isolating global queue polling from a failed test's pending work.
        db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='CANCELLED',generation=generation+1,due_at=NULL,
                    failure_code='TEST_TEARDOWN',cancelled_by=actor_no WHERE active_submission IS NOT NULL
                """);
    }

    @Test
    void imageApprovalIsPrivateUntilAtomicPublicationAndReplaysApproval() throws Exception {
        var fixture = newImageApproval();
        String approvalPath = adminPath(fixture.submission())+"/image-approvals";
        assertEquals(401,post(null,approvalPath,fixture.key(),fixture.input()).statusCode());
        assertEquals(403,post(fixture.owner(),approvalPath,fixture.key(),fixture.input()).statusCode());
        for (var invalid : List.of("rights","attachmentId","revisionNo","expectedVersion")) {
            var input = new LinkedHashMap<>(fixture.input());
            input.remove(invalid);
            assertEquals(422,post(fixture.admin(),approvalPath,fixture.key(),input).statusCode(),invalid);
        }
        var wrong = new LinkedHashMap<>(fixture.input());
        wrong.put("attachmentId",uploadedAsset(member("ROLE_USER")));
        assertEquals(409,post(fixture.admin(),approvalPath,fixture.key(),wrong).statusCode());
        wrong.putAll(fixture.input());
        wrong.put("revisionNo",2);
        assertEquals(409,post(fixture.admin(),approvalPath,fixture.key(),wrong).statusCode());
        wrong.putAll(fixture.input());
        wrong.put("expectedVersion",1);
        assertEquals(409,post(fixture.admin(),approvalPath,fixture.key(),wrong).statusCode());
        wrong.putAll(fixture.input());
        wrong.put("rights",Map.of("decision","GRANTED","scope","PUBLIC_WEB","evidence","합성",
                "validFrom","2020-01-01T00:00:00Z","validUntil","2020-02-01T00:00:00Z"));
        assertEquals(422,post(fixture.admin(),approvalPath,fixture.key(),wrong).statusCode());

        var queued = enqueue(fixture);
        long job = queued.path("id").longValue();
        assertEquals("PENDING",queued.path("state").asString());
        assertEquals(queued,enqueue(fixture));
        assertEquals(409,post(fixture.admin(),approvalPath,UUID.randomUUID(),fixture.input()).statusCode());
        wrong = new LinkedHashMap<>(fixture.input());
        wrong.put("reason","다른 사유");
        assertEquals(409,post(fixture.admin(),approvalPath,fixture.key(),wrong).statusCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?",Integer.class,fixture.asset()));
        assertEquals(0,countCatalogName(fixture.name()));
        assertEquals(409,post(fixture.owner(),ROOT+"/"+fixture.submission()+"/cancel",null,Map.of("expectedVersion",0)).statusCode());
        assertEquals(409,reviseAsAdmin(fixture.admin(),fixture.submission(),0,draft(null)).statusCode());
        assertEquals(409,decideAsAdmin(fixture.admin(),fixture.submission(),UUID.randomUUID(),"REJECT",0,1).statusCode());
        assertEquals(409,approve(fixture.admin(),fixture.submission(),UUID.randomUUID(),0,1).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM PUBLIC_SAKE_IMAGE WHERE asset_id=?",Integer.class,fixture.asset()));

        imageWorker.runOnce();
        var result = jobView(fixture);
        assertEquals(job,result.path("id").longValue());
        assertEquals("COMPLETED",result.path("state").asString(),result.toString());
        assertEquals(1,result.path("attempts").intValue());
        long sake = result.path("approval").path("sakeNo").longValue();
        assertTrue(sake>0);
        var ownerSummary = node(get(fixture.owner(),ROOT+"/"+fixture.submission())).path("submission");
        assertEquals(sake,ownerSummary.path("sakeNo").longValue());
        assertFalse(ownerSummary.path("approvalInProgress").booleanValue());
        assertEquals(result,enqueue(fixture));
        String publicPath = "/api/images/sakes/"+sake+"/"+fixture.asset();
        var served = get(null,publicPath);
        assertEquals(200,served.statusCode());
        assertTrue(served.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("nosniff",served.headers().firstValue("X-Content-Type-Options").orElseThrow());
        String objectKey = db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area='DELIVERY'",
                String.class,fixture.asset());
        assertArrayEquals(staging.objects.get(objectKey).bytes(),served.body());
        assertEquals(1,delivery.writes.get(objectKey));
        assertEquals(publicPath,node(get(null,"/api/sake/"+sake)).path("imageUrl").asString());
        publication.recordRights(UUID.fromString(fixture.asset()),
                com.sakezuki.backend.image.ImagePublicationService.Decision.REVOKED,"합성 철회","fixture",null,null);
        assertEquals(404,get(null,publicPath).statusCode());
        assertTrue(node(get(null,"/api/sake/"+sake)).path("imageUrl").isNull());
        db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=?",fixture.admin().no());
        assertEquals(403,post(fixture.admin(),approvalPath,fixture.key(),fixture.input()).statusCode());
    }

    @Test
    void lostDeliveryPutResponseReusesTheSameBytesAndObjectOnRetry() throws Exception {
        var fixture = newImageApproval();
        long job = enqueue(fixture).path("id").longValue();
        delivery.afterWrite = () -> { throw new IOException("synthetic lost response"); };
        try {
            imageWorker.runOnce();
        } finally {
            delivery.afterWrite = () -> {};
        }
        var failed = jobView(fixture);
        assertEquals("RETRYABLE_FAILURE",failed.path("state").asString(),failed.toString());
        assertEquals("DELIVERY_PUT_IO",failed.path("failureCode").asString());
        assertEquals(0,countCatalogName(fixture.name()));
        imageWorker.runOnce();
        assertEquals(1,jobView(fixture).path("attempts").intValue());
        assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+job+"/retry",null,Map.of()).statusCode());
        imageWorker.runOnce();
        var complete = jobView(fixture);
        assertEquals("COMPLETED",complete.path("state").asString(),complete.toString());
        assertEquals(failed.path("deliveryObjectId"),complete.path("deliveryObjectId"));
        assertEquals(2,complete.path("attempts").intValue());
        String objectKey = db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE id=?",
                String.class,complete.path("deliveryObjectId").longValue());
        assertEquals(1,delivery.writes.get(objectKey));
    }

    @ParameterizedTest
    @ValueSource(strings={"RIGHTS","ADMIN","CANCEL"})
    void changesWhileStorageIsInFlightCannotPublish(String change) throws Exception {
        var fixture = newImageApproval();
        long job = enqueue(fixture).path("id").longValue();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        delivery.beforeWrite = () -> { entered.countDown(); await(resume); };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(imageWorker::runOnce);
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals(0,countCatalogName(fixture.name()));
                if ("RIGHTS".equals(change)) {
                    publication.recordRights(UUID.fromString(fixture.asset()),
                            com.sakezuki.backend.image.ImagePublicationService.Decision.REVOKED,"합성 철회","fixture",null,null);
                } else if ("ADMIN".equals(change)) {
                    db.update("DELETE FROM MEMBER_AUTHORITY WHERE member_no=?",fixture.admin().no());
                } else {
                    assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+job+"/cancel",null,Map.of()).statusCode());
                    assertEquals(200,post(fixture.owner(),ROOT+"/"+fixture.submission()+"/cancel",null,
                            Map.of("expectedVersion",0)).statusCode());
                }
            } finally {
                resume.countDown();
            }
            pending.get(10,TimeUnit.SECONDS);
        } finally {
            delivery.beforeWrite = () -> {};
        }
        var result = jobView(fixture,member("ROLE_ADMIN"));
        assertEquals("CANCEL".equals(change) ? "CANCELLED" : "REQUIRES_REVIEW",result.path("state").asString(),result.toString());
        assertEquals(0,countCatalogName(fixture.name()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id=?",Integer.class,fixture.submission()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM PUBLIC_SAKE_IMAGE WHERE asset_id=?",Integer.class,fixture.asset()));
    }

    @Test
    void expiredWorkerCannotAdvanceAReplacementObjectsGeneration() throws Exception {
        var fixture = newImageApproval();
        long id = enqueue(fixture).path("id").longValue();
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        ImageApprovalJobs.Job first;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { await(start); return tx.execute(status -> imageJobs.claim()); });
            var two = pool.submit(() -> { await(start); return tx.execute(status -> imageJobs.claim()); });
            start.countDown();
            var claims = List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertEquals(1,claims.stream().filter(Optional::isPresent).count());
            first = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        }
        assertEquals(id,first.id());
        db.update("UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET due_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
        var second = tx.execute(status -> imageJobs.claim()).orElseThrow();
        assertEquals(first.generation()+1,second.generation());
        try (var permit = images.admit()) {
            assertThrows(ImageApprovalJobs.Superseded.class,() -> permit.promote(first.asset(),imageApprovals.promotion(first)));
            imageJobs.fail(first,"LATE_FAILURE",false);
            assertEquals("PROCESSING",jobView(fixture).path("state").asString());
            var prepared = permit.promote(second.asset(),imageApprovals.promotion(second));
            assertThrows(ImageApprovalJobs.Superseded.class,() -> imageApprovals.complete(first,prepared));
            imageApprovals.complete(second,prepared);
        }
        assertEquals("COMPLETED",jobView(fixture).path("state").asString());
        assertEquals(2,jobView(fixture).path("attempts").intValue());
    }

    @Test
    void sharedAdmissionDoesNotReserveUploadQuotaOrWorkerAttempts() throws Exception {
        var fixture = newImageApproval();
        enqueue(fixture);
        try (var permit = images.admit()) {
            imageWorker.runOnce();
            assertEquals(0,jobView(fixture).path("attempts").intValue());
        }

        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        delivery.beforeWrite = () -> { entered.countDown(); await(resume); };
        var other = member("ROLE_USER");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = pool.submit(imageWorker::runOnce);
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                for (var owner : List.of(fixture.owner(),other)) {
                    long rows = db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",Long.class,owner.no());
                    long bytes = db.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",
                            Long.class,owner.no());
                    assertEquals(503,upload(owner,UUID.randomUUID(),"image/jpeg",image("jpeg")).statusCode());
                    assertEquals(rows,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",Long.class,owner.no()));
                    assertEquals(bytes,db.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",
                            Long.class,owner.no()));
                }
            } finally {
                resume.countDown();
            }
            running.get(10,TimeUnit.SECONDS);
        } finally {
            delivery.beforeWrite = () -> {};
        }
        assertEquals("COMPLETED",jobView(fixture).path("state").asString());
        assertEquals(201,upload(other,UUID.randomUUID(),"image/jpeg",image("jpeg")).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings={"SAKE_IMAGE","SUBMISSION_APPROVAL","SUBMISSION_EVENT","SUBMISSION_IMAGE_APPROVAL_JOB"})
    void finalPublicationFailureRollsBackCatalogAndOnlyLeavesPrivateDelivery(String table) throws Exception {
        var fixture = newImageApproval();
        enqueue(fixture);
        String constraint = switch (table) {
            case "SAKE_IMAGE" -> "asset_id <> '"+fixture.asset()+"'";
            case "SUBMISSION_APPROVAL" -> "submission_id <> '"+fixture.submission()+"'";
            case "SUBMISSION_EVENT" -> "submission_id <> '"+fixture.submission()+"' OR next_status <> 'APPROVED'";
            default -> "submission_id <> '"+fixture.submission()+"' OR state <> 'COMPLETED'";
        };
        db.execute("ALTER TABLE "+table+" ADD CONSTRAINT fixture_image_approval_failure CHECK ("+constraint+")");
        try {
            imageWorker.runOnce();
        } finally {
            db.execute("ALTER TABLE "+table+" DROP CHECK fixture_image_approval_failure");
        }
        var failed = jobView(fixture);
        assertEquals("REQUIRES_REVIEW",failed.path("state").asString(),failed.toString());
        assertEquals(0,countCatalogName(fixture.name()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SAKE_IMAGE WHERE asset_id=?",Integer.class,fixture.asset()));
        assertEquals("VERIFIED",db.queryForObject("SELECT storage_state FROM IMAGE_OBJECT WHERE id=?",String.class,
                failed.path("deliveryObjectId").longValue()));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM CATALOG_SUBMISSION WHERE id=?",String.class,fixture.submission()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_APPROVAL WHERE submission_id=?",Integer.class,fixture.submission()));
        var replacement = post(fixture.admin(),adminPath(fixture.submission())+"/image-approvals",UUID.randomUUID(),fixture.input());
        assertEquals(202,replacement.statusCode(),string(replacement));
        imageWorker.runOnce();
        var reused = jobView(fixture);
        assertEquals("COMPLETED",reused.path("state").asString(),reused.toString());
        assertEquals(failed.path("deliveryObjectId"),reused.path("deliveryObjectId"));
    }

    @Test
    void realV9LockTimeoutPreservesVerifiedCopyAndRetriesTheSameJob() throws Exception {
        var fixture = newImageApproval();
        long id = enqueue(fixture).path("id").longValue();
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            tx.executeWithoutResult(status -> {
                db.queryForObject("SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id=1 FOR UPDATE",Integer.class);
                var worker = pool.submit(imageWorker::runOnce);
                try {
                    worker.get(12,TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        }
        var parked = jobView(fixture);
        assertEquals("RETRYABLE_FAILURE",parked.path("state").asString(),parked.toString());
        assertEquals("DB_LOCK_CONTENTION",parked.path("failureCode").asString());
        assertEquals("VERIFIED",db.queryForObject("SELECT storage_state FROM IMAGE_OBJECT WHERE id=?",String.class,
                parked.path("deliveryObjectId").longValue()));
        assertEquals(0,countCatalogName(fixture.name()));
        assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+id+"/retry",null,Map.of()).statusCode());
        imageWorker.runOnce();
        assertEquals("COMPLETED",jobView(fixture).path("state").asString());
    }

    @Test
    void lateStagingReadCannotCreateDeliveryIntentAfterNewWorkerClaims() throws Exception {
        var fixture = newImageApproval();
        long id = enqueue(fixture).path("id").longValue();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        staging.afterRead = () -> { entered.countDown(); await(resume); };
        ImageApprovalJobs.Job replacement;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = pool.submit(imageWorker::runOnce);
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                db.update("UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET due_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
                var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
                replacement = tx.execute(status -> imageJobs.claim()).orElseThrow();
            } finally {
                resume.countDown();
            }
            old.get(10,TimeUnit.SECONDS);
        } finally {
            staging.afterRead = () -> {};
        }
        assertEquals(2,replacement.generation());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area='DELIVERY'",
                Integer.class,fixture.asset()));
        try (var permit = images.admit()) {
            var prepared = permit.promote(replacement.asset(),imageApprovals.promotion(replacement));
            assertEquals(0,countCatalogName(fixture.name()));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM PUBLIC_SAKE_IMAGE WHERE asset_id=?",Integer.class,fixture.asset()));
            imageApprovals.complete(replacement,prepared);
        }
        assertEquals("COMPLETED",jobView(fixture).path("state").asString());
    }

    @Test
    void jobRegistrationRollsBackGrantAndDatabaseRejectsInvalidActiveRows() throws Exception {
        var fixture = newImageApproval();
        db.execute("ALTER TABLE SUBMISSION_IMAGE_APPROVAL_JOB ADD CONSTRAINT fixture_image_registration_failure CHECK "
                +"(submission_id <> '"+fixture.submission()+"')");
        try {
            assertEquals(500,post(fixture.admin(),adminPath(fixture.submission())+"/image-approvals",fixture.key(),fixture.input()).statusCode());
        } finally {
            db.execute("ALTER TABLE SUBMISSION_IMAGE_APPROVAL_JOB DROP CHECK fixture_image_registration_failure");
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM IMAGE_RIGHTS_EVENT WHERE asset_id=?",Integer.class,fixture.asset()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_IMAGE_APPROVAL_JOB WHERE submission_id=?",
                Integer.class,fixture.submission()));
        long first = enqueue(fixture).path("id").longValue();
        var invalid = assertThrows(org.springframework.dao.DataAccessException.class,() -> db.update(
                "UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET due_at=NULL WHERE id=?",first));
        assertEquals(3819,((java.sql.SQLException)invalid.getMostSpecificCause()).getErrorCode());
        assertThrows(DataIntegrityViolationException.class,() -> db.update(
                "UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET revision_no=99 WHERE id=?",first));
        assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+first+"/cancel",null,Map.of()).statusCode());
        assertEquals(202,post(fixture.admin(),adminPath(fixture.submission())+"/image-approvals",UUID.randomUUID(),fixture.input()).statusCode());
        assertThrows(DataIntegrityViolationException.class,() -> db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='PENDING',due_at=UTC_TIMESTAMP(6),
                  failure_code=NULL,cancelled_by=NULL WHERE id=?
                """,first));
        imageWorker.runOnce();
        assertEquals("COMPLETED",jobView(fixture).path("state").asString());
        var history = get(fixture.admin(),jobPath(fixture)+"/"+first);
        assertEquals(200,history.statusCode());
        assertEquals("CANCELLED",node(history).path("state").asString());
    }

    @ParameterizedTest
    @ValueSource(strings={"STAGING_MISSING","STAGING_DAMAGED","DELIVERY_DAMAGED"})
    void permanentMediaFailureNeedsReviewAndNeverOverwrites(String reason) throws Exception {
        var fixture = newImageApproval();
        enqueue(fixture);
        String key = db.queryForObject("SELECT object_key FROM IMAGE_OBJECT WHERE asset_id=? AND storage_area='STAGING'",
                String.class,fixture.asset());
        var original = staging.objects.get(key);
        byte[] changed = original.bytes().clone();
        changed[0] ^= 1;
        var corrupt = new ImageStorage.Payload(original.contentType(),original.cacheControl(),original.declaredSha256(),changed);
        switch (reason) {
            case "STAGING_MISSING" -> staging.objects.remove(key);
            case "STAGING_DAMAGED" -> staging.objects.put(key,corrupt);
            default -> delivery.objects.put(key,corrupt);
        }
        int writes = delivery.writeRequests.get();
        imageWorker.runOnce();
        var review = jobView(fixture);
        assertEquals("REQUIRES_REVIEW",review.path("state").asString(),review.toString());
        assertEquals(reason,review.path("failureCode").asString());
        assertEquals(writes,delivery.writeRequests.get());
        assertEquals(0,countCatalogName(fixture.name()));
    }

    @Test
    void automaticRetryBudgetReleasesSubmissionAfterFourStorageFailures() throws Exception {
        var fixture = newImageApproval();
        long id = enqueue(fixture).path("id").longValue();
        delivery.writesFail = true;
        try {
            for (int attempt=1; attempt<=4; attempt++) {
                imageWorker.runOnce();
                var result = jobView(fixture);
                assertEquals(attempt,result.path("attempts").intValue());
                if (attempt<4) {
                    assertEquals("RETRYABLE_FAILURE",result.path("state").asString());
                    assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+id+"/retry",null,Map.of()).statusCode());
                } else {
                    assertEquals("REQUIRES_REVIEW",result.path("state").asString());
                    assertEquals(409,post(fixture.admin(),jobPath(fixture)+"/"+id+"/retry",null,Map.of()).statusCode());
                }
            }
        } finally {
            delivery.writesFail = false;
        }
        assertEquals(200,post(fixture.owner(),ROOT+"/"+fixture.submission()+"/cancel",null,Map.of("expectedVersion",0)).statusCode());
    }

    @Test
    void ownerAndAdminReadOnlyApprovalStateWithTheirOwnProjections() throws Exception {
        var fixture = newImageApproval();
        long job = enqueue(fixture).path("id").longValue();
        assertOwnerJobFlag(fixture,true);
        assertEquals(404,get(member("ROLE_USER"),ROOT+"/"+fixture.submission()).statusCode());
        var adminPage = node(get(fixture.admin(),ADMIN+"?status=PENDING&size=50"));
        var summary = item(adminPage,fixture.submission()).path("approvalJob");
        assertEquals(job,summary.path("id").longValue());
        assertTrue(summary.path("active").booleanValue());
        assertEquals("PENDING",summary.path("state").asString());
        db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='PROCESSING',generation=1,attempts=1,
                    due_at=UTC_TIMESTAMP(6)+INTERVAL 1 MINUTE,last_attempt_at=UTC_TIMESTAMP(6) WHERE id=?
                """,job);
        assertOwnerJobFlag(fixture,true);
        db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='RETRYABLE_FAILURE',failure_code='TEST_STORAGE'
                 WHERE id=?
                """,job);
        assertOwnerJobFlag(fixture,true);
        var adminDetail = node(get(fixture.admin(),adminPath(fixture.submission())));
        assertEquals("TEST_STORAGE",adminDetail.path("submission").path("approvalJob").path("failureCode").asString());
        assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+job+"/cancel",null,Map.of()).statusCode());
        assertOwnerJobFlag(fixture,false);
        var next = post(fixture.admin(),adminPath(fixture.submission())+"/image-approvals",UUID.randomUUID(),fixture.input());
        assertEquals(202,next.statusCode(),string(next));
        long latest = node(next).path("id").longValue();
        db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='REQUIRES_REVIEW',failure_code='TEST_REVIEW',due_at=NULL
                WHERE id=?
                """,latest);
        assertOwnerJobFlag(fixture,false);
        var recent = node(get(fixture.admin(),adminPath(fixture.submission()))).path("submission").path("approvalJob");
        assertEquals(latest,recent.path("id").longValue());
        assertFalse(recent.path("active").booleanValue());
    }

    @Test
    void readyUploadReplayDoesNotNeedTheImagePermitOrUseStorage() throws Exception {
        var owner = member("ROLE_USER");
        var key = UUID.randomUUID();
        byte[] bytes = image("png");
        var first = upload(owner,key,"image/png",bytes);
        assertEquals(201,first.statusCode());
        int reads = staging.readRequests.get();
        int writes = staging.writeRequests.get();
        try (var permit = images.admit()) {
            var replay = upload(owner,key,"image/png",bytes);
            assertEquals(201,replay.statusCode(),string(replay));
            assertEquals(node(first),node(replay));
            assertEquals(409,upload(owner,key,"image/jpeg",image("jpeg")).statusCode());
            assertEquals(503,upload(owner,UUID.randomUUID(),"image/jpeg",image("jpeg")).statusCode());
        }
        assertEquals(reads,staging.readRequests.get());
        assertEquals(writes,staging.writeRequests.get());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?",Integer.class,owner.no()));
    }

    @Test
    void disabledWorkerRejectsRetryButKeepsExistingReadReplayAndCancellation() throws Exception {
        var fixture = newImageApproval();
        long job = enqueue(fixture).path("id").longValue();
        db.update("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='RETRYABLE_FAILURE',generation=1,attempts=1,
                    failure_code='TEST_STORAGE',last_attempt_at=UTC_TIMESTAMP(6) WHERE id=?
                """,job);
        Object service = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(imageApprovals);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",false);
        try {
            String retry = jobPath(fixture)+"/"+job+"/retry";
            assertEquals(403,post(fixture.owner(),retry,null,Map.of()).statusCode());
            assertEquals("RETRYABLE_FAILURE",jobView(fixture).path("state").asString());
            assertEquals(503,post(fixture.admin(),retry,null,Map.of()).statusCode());
            assertEquals(job,enqueue(fixture).path("id").longValue());
            assertEquals(200,post(fixture.admin(),jobPath(fixture)+"/"+job+"/cancel",null,Map.of()).statusCode());
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        }
    }

    private void assertOwnerJobFlag(ImageApprovalFixture fixture, boolean active) throws Exception {
        var detail = get(fixture.owner(),ROOT+"/"+fixture.submission());
        assertEquals(200,detail.statusCode());
        var submission = node(detail).path("submission");
        assertTrue(submission.path("approvalInProgress").isBoolean());
        assertEquals(active,submission.path("approvalInProgress").booleanValue());
        assertEquals("PENDING",submission.path("status").asString());
        assertTrue(submission.path("sakeNo").isNull());
        for (String internal : List.of("approvalJob","failureCode","rightsEventId","leaseUntil","generation","objectKey")) {
            assertFalse(string(detail).contains(json.writeValueAsString(internal)),internal);
        }
        assertEquals(active,item(node(get(fixture.owner(),ROOT)),fixture.submission()).path("approvalInProgress").booleanValue());
    }

    private static JsonNode item(JsonNode page, String id) {
        for (var item : page.path("items")) if (id.equals(item.path("id").asString())) return item;
        throw new AssertionError("submission is missing from page");
    }

    private record ImageApprovalFixture(Member owner,Member admin,String name,String submission,String asset,UUID key,
                                        Map<String,Object> input) {}

    private ImageApprovalFixture newImageApproval() throws Exception {
        var owner = member("ROLE_USER");
        var admin = member("ROLE_ADMIN");
        String asset = uploadedAsset(owner);
        var data = catalogDraft("公開画像");
        String name = ((Map<?,?>)data.get("sake")).get("ja").toString();
        data.putAll(Map.of("attachmentId",asset,"imageRights","합성 이미지 사용 설명","imageConsent",true));
        String id = submit(owner,data);
        var input = Map.<String,Object>of("expectedVersion",0,"revisionNo",1,"reason","합성 제보 승인","identityConfirmed",true,
                "attachmentId",asset,"rights",Map.of("decision","GRANTED","scope","PUBLIC_WEB","evidence","합성 자료의 공개 근거",
                        "validFrom","2020-01-01T00:00:00Z"));
        return new ImageApprovalFixture(owner,admin,name,id,asset,UUID.randomUUID(),input);
    }

    private JsonNode enqueue(ImageApprovalFixture fixture) throws Exception {
        var response = post(fixture.admin(),adminPath(fixture.submission())+"/image-approvals",fixture.key(),fixture.input());
        assertEquals(202,response.statusCode(),string(response));
        assertEquals(jobPath(fixture),response.headers().firstValue("Location").orElseThrow());
        return node(response);
    }

    private JsonNode jobView(ImageApprovalFixture fixture) throws Exception {
        return jobView(fixture,fixture.admin());
    }

    private JsonNode jobView(ImageApprovalFixture fixture, Member observer) throws Exception {
        var response = get(observer,jobPath(fixture));
        assertEquals(200,response.statusCode(),string(response));
        return node(response);
    }

    private static String jobPath(ImageApprovalFixture fixture) { return adminPath(fixture.submission())+"/approval-job"; }

    private static void assertImageDenied(int code,HttpResponse<?> response) {
        assertEquals(code,response.statusCode());
        assertFalse(response.headers().firstValue("Content-Type").orElse("").startsWith("image/"));
        assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10,TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private int raceAction(Member actor,String id,String action) throws Exception {
        return switch (action) {
            case "REVISION" -> reviseAsAdmin(actor,id,0,draft(null)).statusCode();
            case "CANCEL" -> post(actor,ROOT+"/"+id+"/cancel",null,Map.of("expectedVersion",0)).statusCode();
            default -> decideAsAdmin(actor,id,UUID.randomUUID(),action,0,1).statusCode();
        };
    }

    private String uploadedAsset(Member owner) throws Exception {
        var response = upload(owner,UUID.randomUUID(),"image/png",image("png"));
        assertEquals(201,response.statusCode(),string(response));
        return node(response).get("attachmentId").asString();
    }

    private String submit(Member owner,Map<String,Object> draft) throws Exception {
        var response = post(owner,ROOT,UUID.randomUUID(),draft);
        assertEquals(201,response.statusCode(),string(response));
        return node(response).get("id").asString();
    }

    private HttpResponse<byte[]> reviseAsAdmin(Member admin,String id,long version,Map<String,Object> data) throws Exception {
        return post(admin,adminPath(id)+"/revisions",null,
                Map.of("expectedVersion",version,"reason","검수 수정","data",data));
    }

    private HttpResponse<byte[]> decideAsAdmin(Member admin,String id,UUID key,Object action,long version,int revision) throws Exception {
        return post(admin,adminPath(id)+"/decisions",key,
                Map.of("expectedVersion",version,"revisionNo",revision,"decision",action,"reason","검수 결정"));
    }

    private static String adminPath(String id) { return ADMIN+"/"+id; }

    private static Map<String, Object> draft(String image) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sake", Map.of("ja", "合成酒", "ko", "합성 사케"));
        input.put("brewery", Map.of("existingNo", 1));
        input.put("sakeMeter", Map.of("min", -12.5));
        if (image != null) {
            input.put("attachmentId", image);
            input.put("imageRights", "합성 이미지");
            input.put("imageConsent", true);
        }
        return input;
    }

    private void assertInvalid(Member member, Map<String, Object> data) throws Exception {
        assertEquals(422, post(member, ROOT, UUID.randomUUID(), data).statusCode());
    }

    private Member member(String role) {
        String id = UUID.randomUUID().toString();
        String email = id + "@example.invalid";
        db.update("INSERT INTO MEMBER(email,name,address1,phone) VALUES(?,?,?,?)", email, "테스트", "테스트", id.substring(0, 20));
        long no = db.queryForObject("SELECT no FROM MEMBER WHERE email=?", Long.class, email);
        db.update("INSERT INTO MEMBER_AUTHORITY(member_no,authority) VALUES(?,?)", no, role);
        String jwt = tokens.createAccessToken(new CustomUserDetails(no, email, null, List.of(new SimpleGrantedAuthority(role))));
        return new Member(no, jwt);
    }

    private record Member(long no, String jwt) {}

    private HttpRequest.Builder request(Member member, String path) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (member != null) builder.header("Authorization", "Bearer " + member.jwt());
        return builder;
    }

    private HttpResponse<byte[]> get(Member member, String path) throws Exception {
        return http.send(request(member, path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> post(Member member, String path, UUID key, Object body) throws Exception {
        return postJson(member, path, key, json.writeValueAsString(body), false);
    }

    private HttpResponse<byte[]> postJson(Member member, String path, UUID key, String body, boolean chunked) throws Exception {
        var builder = request(member, path).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key.toString());
        return sendBody(builder, HttpRequest.BodyPublishers.ofString(body), chunked);
    }

    private HttpResponse<byte[]> upload(Member member, UUID key, String mime, byte[] bytes) throws Exception {
        String boundary = "ugc-fixture-" + UUID.randomUUID();
        return uploadBody(member, key, boundary, HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(part(boundary, "file", mime)),
                HttpRequest.BodyPublishers.ofByteArray(bytes),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")), false);
    }

    private static String part(String boundary, String name, String mime) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"fixture\"\r\nContent-Type: " + mime + "\r\n\r\n";
    }

    private HttpResponse<byte[]> uploadBody(Member member, UUID key, String boundary, HttpRequest.BodyPublisher body,
                                           boolean chunked) throws Exception {
        var builder = request(member, ROOT + "/uploads").header("Idempotency-Key", key.toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        return sendBody(builder, body, chunked);
    }

    private HttpResponse<byte[]> sendBody(HttpRequest.Builder request, HttpRequest.BodyPublisher body,
                                         boolean chunked) throws Exception {
        if (chunked) body = HttpRequest.BodyPublishers.fromPublisher(body);
        return http.send(request.version(HttpClient.Version.HTTP_1_1).POST(body).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode node(HttpResponse<byte[]> response) { return json.readTree(response.body()); }
    private static String string(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }

    private static byte[] image(String format) throws IOException {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xABCDE0);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        return bytes.toByteArray();
    }

    private static byte[] pixelBomb() throws IOException {
        byte[] bytes = image("png");
        var header = java.nio.ByteBuffer.wrap(bytes);
        header.putInt(16, 5000);
        header.putInt(20, 5000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        header.putInt(29, (int) crc.getValue());
        return bytes;
    }

    static final class FakeStore implements ImageStorage.Readable {
        final Map<String, Payload> objects = new ConcurrentHashMap<>();
        final Map<String, Integer> writes = new ConcurrentHashMap<>();
        final AtomicInteger readRequests = new AtomicInteger();
        final AtomicInteger writeRequests = new AtomicInteger();
        volatile boolean writesFail;
        volatile Probe beforeWrite = () -> {};
        volatile Probe afterWrite = () -> {};
        volatile Probe afterRead = () -> {};

        @Override public boolean createIfAbsent(ObjectKey key, String mime, String hash, byte[] bytes) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            writeRequests.incrementAndGet();
            beforeWrite.run();
            if (writesFail) throw new IOException("fixture unavailable");
            boolean added = objects.putIfAbsent(key.value(), new Payload(mime, CACHE_CONTROL, hash, bytes.clone())) == null;
            if (added) writes.merge(key.value(), 1, Integer::sum);
            afterWrite.run();
            return added;
        }

        @Override public Optional<ObjectInfo> read(ObjectKey key, int max) throws IOException {
            return readPayload(key, max).map(p -> new ObjectInfo(p.contentType(), p.cacheControl(), p.declaredSha256(),
                    ImageTransform.sha256(p.bytes()), p.bytes().length));
        }

        @Override public Optional<Payload> readPayload(ObjectKey key, int max) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            readRequests.incrementAndGet();
            var p = objects.get(key.value());
            if (p == null) return Optional.empty();
            afterRead.run();
            if (p.bytes().length > max) throw new ReadLimitExceeded();
            return Optional.of(new Payload(p.contentType(), p.cacheControl(), p.declaredSha256(), p.bytes().clone()));
        }
    }

    @FunctionalInterface interface Probe { void run() throws IOException; }
}
