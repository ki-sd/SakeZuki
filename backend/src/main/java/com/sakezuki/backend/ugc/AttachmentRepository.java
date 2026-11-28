package com.sakezuki.backend.ugc;

import com.sakezuki.imagecore.ImageTransform;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class AttachmentRepository {
    private static final String PREVIEW = """
            SELECT o.object_id,o.attempt,o.object_key,o.mime,o.bytes,o.width,o.height,o.sha256
            FROM SUBMISSION_ATTACHMENT a JOIN UGC_VERIFIED_STAGING o ON o.asset_id=a.asset_id
            JOIN MEMBER m ON m.no=a.owner_no AND m.status='ACTIVE'
            """;

    private final JdbcClient sql;

    AttachmentRepository(JdbcClient sql) {
        this.sql = sql;
    }

    record Intent(String id, String hash, String status, long generation, boolean leased, boolean expired,
                  LocalDateTime expiresAt) {}
    record Preview(long objectId, long generation, String key, ImageTransform.Info media) {}

    Optional<Intent> existing(long owner, UUID key) {
        return sql.sql("""
                SELECT a.*, lease_until > UTC_TIMESTAMP(6) AS leased,
                    expires_at <= UTC_TIMESTAMP(6) AS expired FROM SUBMISSION_ATTACHMENT a
                WHERE owner_no=? AND request_key=?
                """).params(owner, key.toString()).query(AttachmentRepository::intent).optional();
    }

    Optional<Intent> ready(long owner, UUID key) {
        return sql.sql("""
                SELECT a.*, FALSE AS leased, expires_at <= UTC_TIMESTAMP(6) AS expired
                FROM SUBMISSION_ATTACHMENT a JOIN MEMBER m ON m.no=a.owner_no AND m.status='ACTIVE'
                WHERE a.owner_no=? AND a.request_key=? AND a.status='READY'
                """).params(owner,key.toString()).query(AttachmentRepository::intent).optional();
    }

    boolean hasLiveCopy(long owner, String hash) {
        return sql.sql("""
                SELECT id FROM SUBMISSION_ATTACHMENT WHERE owner_no=? AND request_hash=?
                  AND status IN ('UPLOADING','RETRYABLE_FAILURE','READY')
                  AND (submission_id IS NOT NULL OR expires_at > UTC_TIMESTAMP(6))
                LIMIT 1
                """).params(owner, hash).query(String.class).optional().isPresent();
    }

    long recent(long owner) {
        return sql.sql("""
                SELECT COUNT(*) FROM SUBMISSION_ATTACHMENT
                WHERE owner_no=? AND created_at > UTC_TIMESTAMP(6) - INTERVAL 1 DAY
                """).param(owner).query(Long.class).single();
    }

    long reservedBytes(long owner) {
        return sql.sql("SELECT COALESCE(SUM(reserved_bytes),0) FROM SUBMISSION_ATTACHMENT WHERE owner_no=?")
                .param(owner).query(Long.class).single();
    }

    void insert(long owner, UUID id, UUID key, String hash, int reserved, int expiryHours) {
        sql.sql("""
                INSERT INTO SUBMISSION_ATTACHMENT(id,owner_no,request_key,request_hash,reserved_bytes,status,
                    generation,lease_until,expires_at)
                VALUES (?,?,?,?,?,'UPLOADING',1,UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,UTC_TIMESTAMP(6) + INTERVAL ? HOUR)
                """).params(id.toString(), owner, key.toString(), hash, reserved, expiryHours).update();
    }

    void reclaim(long owner, Intent intent) {
        if (sql.sql("""
                UPDATE SUBMISSION_ATTACHMENT SET status='UPLOADING', generation=generation+1,
                  lease_until=UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE
                WHERE owner_no=? AND id=? AND generation=? AND expires_at > UTC_TIMESTAMP(6)
                  AND status IN ('UPLOADING','RETRYABLE_FAILURE')
                  AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                """).params(owner, intent.id(), intent.generation()).update() != 1) throw SubmissionErrors.conflict();
    }

    boolean finish(long owner, Intent intent) {
        return sql.sql("""
                UPDATE SUBMISSION_ATTACHMENT a JOIN UGC_VERIFIED_STAGING o ON o.asset_id=a.id
                SET a.asset_id=o.asset_id,a.status='READY',a.lease_until=NULL,a.reserved_bytes=o.bytes
                WHERE a.owner_no=? AND a.id=? AND a.status='UPLOADING' AND a.generation=?
                  AND a.expires_at > UTC_TIMESTAMP(6)
                """).params(owner, intent.id(), intent.generation()).update() == 1;
    }

    void fail(long owner, Intent intent, String status) {
        sql.sql("""
                UPDATE SUBMISSION_ATTACHMENT SET status=?,lease_until=NULL,
                  reserved_bytes=CASE WHEN ?='INVALID' THEN 0 ELSE reserved_bytes END
                WHERE owner_no=? AND id=? AND status='UPLOADING' AND generation=?
                """).params(status, status, owner, intent.id(), intent.generation()).update();
    }

    void claim(long owner, UUID id, String submission) {
        var eligible = sql.sql("""
                SELECT a.submission_id FROM SUBMISSION_ATTACHMENT a
                JOIN UGC_VERIFIED_STAGING o ON o.asset_id=a.asset_id
                WHERE a.owner_no=? AND a.id=? AND a.status='READY'
                  AND (a.submission_id=? OR (a.submission_id IS NULL AND a.expires_at > UTC_TIMESTAMP(6)))
                FOR UPDATE
                """).params(owner, id.toString(), submission).query((rs, row) -> rs.getString(1) == null).optional();
        if (eligible.isEmpty()) throw SubmissionErrors.missing();
        if (eligible.get()) {
            sql.sql("""
                    UPDATE SUBMISSION_ATTACHMENT SET submission_id=? WHERE owner_no=? AND id=? AND submission_id IS NULL
                    """).params(submission, owner, id.toString()).update();
        }
    }

    Optional<Preview> preview(long owner, UUID asset, UUID submission) {
        if (submission == null) {
            return sql.sql(PREVIEW + """
                    WHERE a.owner_no=? AND a.id=? AND a.status='READY'
                      AND a.submission_id IS NULL AND a.expires_at > UTC_TIMESTAMP(6)
                    """).params(owner, asset.toString()).query(AttachmentRepository::preview).optional();
        }
        return sql.sql(PREVIEW + """
                JOIN CATALOG_SUBMISSION s ON s.id=a.submission_id AND s.owner_no=a.owner_no
                WHERE a.owner_no=? AND a.id=? AND a.status='READY' AND s.id=?
                  AND s.status IN ('PENDING','NEEDS_REVISION')
                  AND EXISTS (SELECT 1 FROM SUBMISSION_REVISION r WHERE r.attachment_id=a.id AND r.submission_id=s.id)
                """).params(owner, asset.toString(), submission.toString()).query(AttachmentRepository::preview).optional();
    }

    Optional<Preview> adminPreview(long actor, UUID submission, UUID attachment) {
        return sql.sql(PREVIEW + """
                JOIN CATALOG_SUBMISSION s ON s.id=a.submission_id AND s.owner_no=a.owner_no
                WHERE s.id=? AND a.id=? AND a.status='READY'
                  AND s.status IN ('PENDING','NEEDS_REVISION')
                  AND EXISTS (SELECT 1 FROM SUBMISSION_REVISION r WHERE r.attachment_id=a.id AND r.submission_id=s.id)
                  AND EXISTS (SELECT 1 FROM MEMBER reviewer JOIN MEMBER_AUTHORITY authority
                    ON authority.member_no=reviewer.no AND authority.authority='ROLE_ADMIN'
                    WHERE reviewer.no=? AND reviewer.status='ACTIVE')
                """).params(submission.toString(), attachment.toString(), actor).query(AttachmentRepository::preview).optional();
    }

    private static Intent intent(ResultSet rs, int row) throws SQLException {
        return new Intent(rs.getString("id"), rs.getString("request_hash"), rs.getString("status"),
                rs.getLong("generation"), rs.getBoolean("leased"), rs.getBoolean("expired"),
                rs.getObject("expires_at", LocalDateTime.class));
    }

    private static Preview preview(ResultSet rs, int row) throws SQLException {
        return new Preview(rs.getLong(1), rs.getLong(2), rs.getString(3),
                new ImageTransform.Info(rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8)));
    }
}
