package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageTransform;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
class ImageAssetRepository {
    private final JdbcClient sql;

    ImageAssetRepository(JdbcClient sql) {
        this.sql = sql;
    }

    record Asset(String source, String evidence, ImageTransform.Info original,
                 ImageTransform.Info transformed, String version) {}
    record Stored(long id, String key, String version, String state, long attempt) {}
    record PublicImage(long linkId, long objectId, long generation, String objectKey, ImageTransform.Info media) {}

    void ensureAsset(UUID id, Asset asset) {
        var original = asset.original();
        var transformed = asset.transformed();
        sql.sql("""
                INSERT INTO IMAGE_ASSET (id, source_type, evidence_ref,
                  original_mime, original_bytes, original_width, original_height, original_sha256,
                  transformed_mime, transformed_bytes, transformed_width, transformed_height, transformed_sha256, transform_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = IMAGE_ASSET.id
                """)
                .params(id.toString(), asset.source(), asset.evidence(),
                        original.mimeType(), original.byteLength(), original.width(), original.height(), original.sha256(),
                        transformed.mimeType(), transformed.byteLength(), transformed.width(), transformed.height(),
                        transformed.sha256(), asset.version()).update();
        if (!asset.equals(lockAsset(id))) throw new IllegalStateException("image asset identity conflict");
    }

    Asset lockAsset(UUID id) {
        return sql.sql("SELECT * FROM IMAGE_ASSET WHERE id = ? FOR UPDATE").param(id.toString())
                .query((rs, row) -> new Asset(rs.getString("source_type"), rs.getString("evidence_ref"),
                        media(rs, "original_"), media(rs, "transformed_"), rs.getString("transform_version")))
                .optional().orElseThrow(() -> new IllegalArgumentException("unknown image asset"));
    }

    void requireGrant(UUID id) {
        if (sql.sql("SELECT id FROM IMAGE_CURRENT_PUBLIC_GRANT WHERE asset_id = ?").param(id.toString())
                .query(Long.class).optional().isEmpty()) {
            throw new IllegalStateException("current public rights grant required");
        }
    }

    boolean hasGrant(UUID id, long grant) {
        return sql.sql("SELECT id FROM IMAGE_CURRENT_PUBLIC_GRANT WHERE asset_id = ? AND id = ?")
                .params(id.toString(),grant).query(Long.class).optional().isPresent();
    }

    boolean verifiedStaging(UUID id, long object, long attempt) {
        return sql.sql("SELECT object_id FROM UGC_VERIFIED_STAGING WHERE asset_id=? AND object_id=? AND attempt=?")
                .params(id.toString(),object,attempt).query(Long.class).optional().isPresent();
    }

    void ensureObject(UUID id, ImageStores.Area area, String key, String version) {
        sql.sql("""
                INSERT INTO IMAGE_OBJECT (asset_id, storage_area, object_key, object_version)
                VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = IMAGE_OBJECT.id
                """).params(id.toString(), area.name(), key, version).update();
    }

    Stored object(UUID id, ImageStores.Area area) {
        return sql.sql("""
                SELECT id, object_key, object_version, storage_state, attempt FROM IMAGE_OBJECT
                WHERE asset_id = ? AND storage_area = ? FOR UPDATE
                """).params(id.toString(), area.name())
                .query((rs, row) -> new Stored(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
                .optional().orElseThrow(() -> new IllegalStateException("missing image object intent"));
    }

    Stored begin(Stored object, boolean recovery) {
        if ("VERIFIED".equals(object.state())) return object;
        String next = recovery ? "PERMANENT_FAILURE" : "PENDING";
        sql.sql("""
                UPDATE IMAGE_OBJECT SET attempt = attempt + 1, storage_state = ?,
                  failure_code = CASE WHEN ? THEN failure_code ELSE NULL END
                WHERE id = ?
                """).params(next, recovery, object.id()).update();
        return new Stored(object.id(), object.key(), object.version(), next, object.attempt() + 1);
    }

    boolean verified(Stored object, ImageTransform.Info media) {
        return sql.sql("""
                UPDATE IMAGE_OBJECT SET storage_state = 'VERIFIED', stored_bytes = ?, stored_sha256 = ?,
                  verified_at = UTC_TIMESTAMP(6), failure_code = NULL, attempt = attempt + 1
                WHERE id = ? AND attempt = ? AND storage_state = ?
                """).params(media.byteLength(), media.sha256(), object.id(), object.attempt(), object.state()).update() == 1;
    }

    void failed(long object, long generation, String expectedState, boolean permanent, String code) {
        sql.sql("""
                UPDATE IMAGE_OBJECT SET storage_state = ?, failure_code = ?, attempt = attempt + 1,
                  stored_bytes = NULL, stored_sha256 = NULL, verified_at = NULL
                WHERE id = ? AND attempt = ? AND storage_state = ?
                """).params(permanent ? "PERMANENT_FAILURE" : "RETRYABLE_FAILURE", code, object,
                generation, expectedState).update();
    }

    long recordRights(UUID id, ImagePublicationService.Decision decision, String evidence, String actor,
                      Instant from, Instant until) {
        var generated = new GeneratedKeyHolder();
        sql.sql("""
                INSERT INTO IMAGE_RIGHTS_EVENT (asset_id, decision, scope, evidence_ref, decided_by, valid_from, valid_until)
                VALUES (?, ?, 'PUBLIC_WEB', ?, ?, ?, ?)
                """).params(id.toString(), decision.name(), evidence, actor, utc(from), utc(until)).update(generated, "id");
        sql.sql("UPDATE SAKE_IMAGE SET withdrawn_at = UTC_TIMESTAMP(6) WHERE asset_id = ? AND withdrawn_at IS NULL")
                .param(id.toString()).update();
        return generated.getKey().longValue();
    }

    void attachPrimary(long sakeNo, UUID id) {
        attachPrimary(sakeNo, id, null, null);
    }

    void attachPrimary(long sakeNo, UUID id, Long object, Long grant) {
        sql.sql("SELECT no FROM SAKE WHERE no = ? FOR UPDATE").param(sakeNo)
                .query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("unknown sake"));
        if (object == null && publicImage(sakeNo, id).isPresent()) return;
        sql.sql("UPDATE SAKE_IMAGE SET withdrawn_at = UTC_TIMESTAMP(6) WHERE active_primary_sake = ?")
                .param(sakeNo).update();
        int inserted = sql.sql("""
                INSERT INTO SAKE_IMAGE (sake_no, asset_id, object_id, rights_event_id, is_primary)
                SELECT :sake, asset_id, object_id, rights_event_id, TRUE
                FROM IMAGE_DELIVERY_CANDIDATE WHERE asset_id = :asset
                  AND (:object IS NULL OR object_id = :object AND rights_event_id = :grant)
                """).param("sake", sakeNo).param("asset", id.toString()).param("object", object).param("grant", grant).update();
        if (inserted != 1) throw new IllegalStateException("verified delivery object and current public grant required");
    }

    Optional<PublicImage> publicImage(long sakeNo, UUID id) {
        return sql.sql("""
                SELECT link_id, object_id, attempt, object_key, mime, byte_length, width, height, sha256
                FROM PUBLIC_SAKE_IMAGE WHERE active_primary_sake = ? AND asset_id = ?
                """).params(sakeNo, id.toString()).query((rs, row) -> new PublicImage(rs.getLong("link_id"), rs.getLong("object_id"), rs.getLong("attempt"),
                rs.getString("object_key"), new ImageTransform.Info(rs.getString("mime"), rs.getInt("byte_length"),
                rs.getInt("width"), rs.getInt("height"), rs.getString("sha256")))).optional();
    }

    private static ImageTransform.Info media(ResultSet rs, String prefix) throws SQLException {
        return new ImageTransform.Info(rs.getString(prefix + "mime"), rs.getInt(prefix + "bytes"),
                rs.getInt(prefix + "width"), rs.getInt(prefix + "height"), rs.getString(prefix + "sha256"));
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
