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
        "ugc.open-submissions=2", "ugc.uploads-per-day=2"})
class UgcMySqlTests {
    private static final String ROOT = UgcController.ROOT;
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider tokens;
    @Autowired FakeStore staging;
    @Autowired AttachmentRepository attachments;
    @Autowired SubmissionRepository submissions;
    @Autowired SubmissionLimits limits;
    @Autowired com.sakezuki.backend.image.ImageAssetService images;
    @Autowired ImageStores stores;
    @Autowired PlatformTransactionManager manager;
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
        @Bean @Primary ImageStores uploadFixtureAreas(FakeStore storage) {
            return area -> area == ImageStores.Area.STAGING ? Optional.of(storage) : Optional.empty();
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
        var another = new AttachmentService(attachments, submissions, limits, images, stores, manager);
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
        volatile Probe afterRead = () -> {};

        @Override public boolean createIfAbsent(ObjectKey key, String mime, String hash, byte[] bytes) throws IOException {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            writeRequests.incrementAndGet();
            beforeWrite.run();
            if (writesFail) throw new IOException("fixture unavailable");
            boolean added = objects.putIfAbsent(key.value(), new Payload(mime, CACHE_CONTROL, hash, bytes.clone())) == null;
            if (added) writes.merge(key.value(), 1, Integer::sum);
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
