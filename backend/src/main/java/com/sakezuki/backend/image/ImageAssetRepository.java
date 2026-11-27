package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageTransform;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
    record PublicImage(long linkId, String objectKey, ImageTransform.Info media) {}
    record CatalogImage(String managedUrl, boolean hasManagedHistory) {}

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

    long begin(Stored object) {
        sql.sql("""
                UPDATE IMAGE_OBJECT SET attempt = attempt + 1, storage_state = 'PENDING', failure_code = NULL
                WHERE id = ?
                """).param(object.id()).update();
        return object.attempt() + 1;
    }

    boolean verified(long object, long attempt, ImageTransform.Info media) {
        return sql.sql("""
                UPDATE IMAGE_OBJECT SET storage_state = 'VERIFIED', stored_bytes = ?, stored_sha256 = ?,
                  verified_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND attempt = ? AND storage_state = 'PENDING'
                """).params(media.byteLength(), media.sha256(), object, attempt).update() == 1;
    }

    void failed(long object, long attempt, boolean retryable, String code) {
        sql.sql("""
                UPDATE IMAGE_OBJECT SET storage_state = ?, failure_code = ?
                WHERE id = ? AND attempt = ? AND storage_state = 'PENDING'
                """).params(retryable ? "RETRYABLE_FAILURE" : "PERMANENT_FAILURE", code, object, attempt).update();
    }

    void recordRights(UUID id, ImagePublicationService.Decision decision, String evidence, String actor,
                      Instant from, Instant until) {
        sql.sql("""
                INSERT INTO IMAGE_RIGHTS_EVENT (asset_id, decision, scope, evidence_ref, decided_by, valid_from, valid_until)
                VALUES (?, ?, 'PUBLIC_WEB', ?, ?, ?, ?)
                """).params(id.toString(), decision.name(), evidence, actor, utc(from), utc(until)).update();
        sql.sql("UPDATE SAKE_IMAGE SET withdrawn_at = UTC_TIMESTAMP(6) WHERE asset_id = ? AND withdrawn_at IS NULL")
                .param(id.toString()).update();
    }

    void attachPrimary(long sakeNo, UUID id) {
        sql.sql("SELECT no FROM SAKE WHERE no = ? FOR UPDATE").param(sakeNo)
                .query(Long.class).optional().orElseThrow(() -> new IllegalArgumentException("unknown sake"));
        if (publicImage(sakeNo, id).isPresent()) return;
        sql.sql("UPDATE SAKE_IMAGE SET withdrawn_at = UTC_TIMESTAMP(6) WHERE active_primary_sake = ?")
                .param(sakeNo).update();
        int inserted = sql.sql("""
                INSERT INTO SAKE_IMAGE (sake_no, asset_id, object_id, rights_event_id, is_primary)
                SELECT ?, asset_id, object_id, rights_event_id, TRUE FROM IMAGE_DELIVERY_CANDIDATE WHERE asset_id = ?
                """).params(sakeNo, id.toString()).update();
        if (inserted != 1) throw new IllegalStateException("verified delivery object and current public grant required");
    }

    Optional<PublicImage> publicImage(long sakeNo, UUID id) {
        return sql.sql("""
                SELECT link_id, object_key, mime, byte_length, width, height, sha256
                FROM PUBLIC_SAKE_IMAGE WHERE active_primary_sake = ? AND asset_id = ?
                """).params(sakeNo, id.toString()).query((rs, row) -> new PublicImage(rs.getLong("link_id"),
                rs.getString("object_key"), new ImageTransform.Info(rs.getString("mime"), rs.getInt("byte_length"),
                rs.getInt("width"), rs.getInt("height"), rs.getString("sha256")))).optional();
    }

    Optional<CatalogImage> catalogImage(long sakeNo) {
        return sql.sql("SELECT managed_url, has_managed_history FROM CATALOG_SAKE_IMAGE_URL WHERE sake_no = ?")
                .param(sakeNo)
                .query((rs, row) -> new CatalogImage(rs.getString(1), rs.getBoolean(2))).optional();
    }

    private static ImageTransform.Info media(ResultSet rs, String prefix) throws SQLException {
        return new ImageTransform.Info(rs.getString(prefix + "mime"), rs.getInt(prefix + "bytes"),
                rs.getInt(prefix + "width"), rs.getInt(prefix + "height"), rs.getString(prefix + "sha256"));
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
