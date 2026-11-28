package com.sakezuki.backend.ugc;

import com.sakezuki.backend.image.ImageAssetService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class ImageApprovalJobs {
    static final int MAX_ATTEMPTS = 4;
    static final int LEASE_SECONDS = 240;
    private static final String ROW = """
            SELECT j.*, (j.state='PROCESSING' AND j.due_at>UTC_TIMESTAMP(6)) AS valid_lease
            FROM SUBMISSION_IMAGE_APPROVAL_JOB j
            """;
    private final JdbcClient sql;

    ImageApprovalJobs(JdbcClient sql) {
        this.sql = sql;
    }

    record Job(long id, String submission, int revision, long expectedVersion, long actor, String key, String hash,
               String reason, UUID asset, long grant, ImageAssetService.ObjectVersion staging, Long delivery,
               String state, long generation, int attempts, boolean validLease, String failure, LocalDateTime due,
               LocalDateTime lastAttempt, Long sake) {}

    void requireInactive(String submission) {
        if (sql.sql("SELECT id FROM SUBMISSION_IMAGE_APPROVAL_JOB WHERE active_submission=?")
                .param(submission).query(Long.class).optional().isPresent()) {
            throw SubmissionErrors.error(409,"이미지 승인 작업이 진행 중입니다.");
        }
    }

    Optional<Job> byKey(long actor, UUID key) {
        return sql.sql(ROW + " WHERE actor_no=? AND request_key=?").params(actor,key.toString())
                .query(ImageApprovalJobs::row).optional();
    }

    Optional<Job> latest(String submission, boolean lock) {
        return sql.sql(ROW + " WHERE submission_id=? ORDER BY id DESC LIMIT 1" + (lock ? " FOR UPDATE" : ""))
                .param(submission).query(ImageApprovalJobs::row).optional();
    }

    void insert(AdminReviewRepository.Head head, long actor, UUID key, String hash, String reason, UUID asset,
                long grant, ImageAssetService.ObjectVersion staging) {
        sql.sql("""
                INSERT INTO SUBMISSION_IMAGE_APPROVAL_JOB
                   (submission_id,revision_no,expected_version,actor_no,request_key,request_hash,reason,
                    attachment_id,asset_id,rights_event_id,staging_object_id,staging_attempt)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """).params(head.id(),head.revision(),head.version(),actor,key.toString(),hash,reason,asset.toString(),
                asset.toString(),grant,staging.id(),staging.attempt()).update();
    }

    Optional<Job> claim() {
        var next = sql.sql(ROW + """
                WHERE due_at<=UTC_TIMESTAMP(6)
                ORDER BY due_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(ImageApprovalJobs::row).optional();
        if (next.isEmpty()) return Optional.empty();
        var job = next.get();
        if (job.attempts()>=MAX_ATTEMPTS) {
            sql.sql("""
                    UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='REQUIRES_REVIEW',generation=generation+1,
                        due_at=NULL,failure_code='ATTEMPTS_EXHAUSTED',updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND generation=?
                    """).params(job.id(),job.generation()).update();
            return Optional.empty();
        }
        sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='PROCESSING',generation=generation+1,attempts=attempts+1,
                    last_attempt_at=UTC_TIMESTAMP(6),due_at=UTC_TIMESTAMP(6)+INTERVAL ? SECOND,
                    failure_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND generation=?
                """).params(LEASE_SECONDS,job.id(),job.generation()).update();
        return byId(job.id());
    }

    void fence(Job job) {
        if (sql.sql(ROW + " WHERE id=? FOR UPDATE").param(job.id()).query(ImageApprovalJobs::row).optional()
                .filter(row -> row.validLease() && row.generation()==job.generation()).isEmpty()) {
            throw new Superseded();
        }
    }

    void deliveryIntent(Job job, long object) {
        sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET delivery_object_id=?,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND generation=? AND state='PROCESSING'
                """).params(object,job.id(),job.generation()).update();
    }

    void complete(Job job, long sake, long delivery) {
        if (sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='COMPLETED',due_at=NULL,result_sake_no=?,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND generation=? AND state='PROCESSING' AND due_at>UTC_TIMESTAMP(6) AND delivery_object_id=?
                """).params(sake,job.id(),job.generation(),delivery).update()!=1) throw new Superseded();
    }

    void fail(Job job, String code, boolean retry) {
        boolean again = retry && job.attempts()<MAX_ATTEMPTS;
        sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state=?,failure_code=?,
                    due_at=IF(? , UTC_TIMESTAMP(6)+INTERVAL ? SECOND,NULL),updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND generation=? AND state='PROCESSING' AND due_at>UTC_TIMESTAMP(6)
                """).params(again ? "RETRYABLE_FAILURE" : "REQUIRES_REVIEW",code,again,30 << (job.attempts()-1),
                job.id(),job.generation()).update();
    }

    void retry(Job job) {
        if (sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET due_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND generation=? AND state='RETRYABLE_FAILURE'
                """).params(job.id(),job.generation()).update()!=1) throw SubmissionErrors.conflict();
    }

    void cancel(Job job, long actor) {
        if (sql.sql("""
                UPDATE SUBMISSION_IMAGE_APPROVAL_JOB SET state='CANCELLED',generation=generation+1,
                    due_at=NULL,failure_code='CANCELLED_BY_ADMIN',cancelled_by=?,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND generation=? AND active_submission IS NOT NULL
                """).params(actor,job.id(),job.generation()).update()!=1) throw SubmissionErrors.conflict();
    }

    Optional<Job> byId(long id) {
        return sql.sql(ROW+" WHERE id=?").param(id).query(ImageApprovalJobs::row).optional();
    }

    private static Job row(ResultSet rs, int index) throws SQLException {
        return new Job(rs.getLong("id"),rs.getString("submission_id"),rs.getInt("revision_no"),rs.getLong("expected_version"),
                rs.getLong("actor_no"),rs.getString("request_key"),rs.getString("request_hash"),rs.getString("reason"),
                UUID.fromString(rs.getString("asset_id")),rs.getLong("rights_event_id"),
                new ImageAssetService.ObjectVersion(rs.getLong("staging_object_id"),rs.getLong("staging_attempt")),
                rs.getObject("delivery_object_id",Long.class),rs.getString("state"),rs.getLong("generation"),
                rs.getInt("attempts"),rs.getBoolean("valid_lease"),rs.getString("failure_code"),
                rs.getObject("due_at",LocalDateTime.class),rs.getObject("last_attempt_at",LocalDateTime.class),
                rs.getObject("result_sake_no",Long.class));
    }

    static final class Superseded extends RuntimeException {}
}
