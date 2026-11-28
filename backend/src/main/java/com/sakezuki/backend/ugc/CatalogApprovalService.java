package com.sakezuki.backend.ugc;

import com.sakezuki.imagecore.ImageTransform;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
class CatalogApprovalService {
    private final AdminReviewAccess access;
    private final AdminReviewRepository reviews;
    private final SubmissionRepository submissions;
    private final CatalogRegistration catalog;
    private final JdbcClient sql;
    private final ObjectMapper json;

    CatalogApprovalService(AdminReviewAccess access, AdminReviewRepository reviews, SubmissionRepository submissions,
                           CatalogRegistration catalog, JdbcClient sql, ObjectMapper json) {
        this.access = access;
        this.reviews = reviews;
        this.submissions = submissions;
        this.catalog = catalog;
        this.sql = sql;
        this.json = json;
    }

    record Request(Long expectedVersion, Integer revisionNo, String reason, Boolean identityConfirmed) {}
    record Receipt(String id, String status, long version, int currentRevision, long sakeNo, LocalDateTime approvedAt) {}
    private record Approval(String hash, Receipt receipt) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt approve(long actor, UUID submission, UUID key, Request request) {
        access.lockCurrent(actor);
        if (request.expectedVersion() == null || request.expectedVersion() < 0) throw SubmissionInput.invalid("expectedVersion");
        if (request.revisionNo() == null || request.revisionNo() < 1) throw SubmissionInput.invalid("revisionNo");
        SubmissionInput.text(request.reason(), 500, true, "reason");
        String hash = ImageTransform.sha256(("catalog-approval-v1\n" + submission + "\n" + json.writeValueAsString(request))
                .getBytes(StandardCharsets.UTF_8));
        var previous = approval(actor, key);
        if (previous.isPresent()) {
            if (!hash.equals(previous.get().hash())) throw SubmissionErrors.conflict();
            return previous.get().receipt();
        }
        if (!Boolean.TRUE.equals(request.identityConfirmed())) throw SubmissionInput.invalid("identityConfirmed");
        var head = reviews.find(submission, true).orElseThrow(SubmissionErrors::missing);
        if (!"PENDING".equals(head.status()) || head.version() != request.expectedVersion()
                || head.revision() != request.revisionNo()) throw SubmissionErrors.conflict();

        var revision = reviews.current(head);
        var data = json.readValue(revision.payload(), SubmissionInput.class);
        if (revision.attachmentId() != null || data.attachmentId() != null) {
            throw SubmissionErrors.error(409, "첨부 이미지가 있습니다. 첨부를 명시적으로 제외한 관리자 리비전이 필요합니다.");
        }
        data.validate();
        String evidence = "submission:" + head.id() + "/revision:" + head.revision();
        long sake = catalog.publish(data, evidence);
        sql.sql("""
                INSERT INTO SUBMISSION_APPROVAL
                    (submission_id,revision_no,sake_no,actor_no,expected_version,result_version,request_key,request_hash,reason)
                VALUES (?,?,?,?,?,?,?,?,?)
                """).params(head.id(),head.revision(),sake,actor,head.version(),head.version()+1,key.toString(),hash,request.reason())
                .update();
        reviews.advance(head, head.revision(), "APPROVED");
        submissions.event(head.id(), head.revision(), actor, "ADMIN", "PENDING", "APPROVED", request.reason());
        return approval(actor, key).orElseThrow().receipt();
    }

    private Optional<Approval> approval(long actor, UUID key) {
        return sql.sql("""
                SELECT request_hash,submission_id,result_version,revision_no,sake_no,created_at
                FROM SUBMISSION_APPROVAL WHERE actor_no=? AND request_key=?
                """).params(actor, key.toString()).query((rs, row) -> new Approval(rs.getString("request_hash"),
                new Receipt(rs.getString("submission_id"),"APPROVED",rs.getLong("result_version"),
                        rs.getInt("revision_no"),rs.getLong("sake_no"),rs.getObject("created_at",LocalDateTime.class)))).optional();
    }
}
