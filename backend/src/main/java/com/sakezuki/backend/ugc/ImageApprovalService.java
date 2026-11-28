package com.sakezuki.backend.ugc;

import com.sakezuki.backend.image.ImageAssetService;
import com.sakezuki.backend.image.ImagePublicationService;
import com.sakezuki.imagecore.ImageTransform;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
class ImageApprovalService {
    private final AdminReviewAccess access;
    private final AdminReviewRepository reviews;
    private final CatalogApprovalService approval;
    private final CatalogRegistration catalog;
    private final ImageApprovalJobs jobs;
    private final ImagePublicationService publication;
    private final ImageAssetService images;
    private final ObjectMapper json;
    private final JdbcClient sql;
    private final boolean enabled;

    ImageApprovalService(AdminReviewAccess access, AdminReviewRepository reviews, CatalogApprovalService approval,
                         CatalogRegistration catalog, ImageApprovalJobs jobs, ImagePublicationService publication,
                         ImageAssetService images, ObjectMapper json, JdbcClient sql,
                         @Value("${ugc.image-approval.enabled:false}") boolean enabled) {
        this.access = access;
        this.reviews = reviews;
        this.approval = approval;
        this.catalog = catalog;
        this.jobs = jobs;
        this.publication = publication;
        this.images = images;
        this.json = json;
        this.sql = sql;
        this.enabled = enabled;
    }

    record Request(Long expectedVersion, Integer revisionNo, String reason, Boolean identityConfirmed,
                   UUID attachmentId, Rights rights) {}
    record Rights(String decision, String scope, String evidence, String validFrom, String validUntil) {}
    record JobView(long id, String submissionId, int revisionNo, String state, int attempts, String failureCode,
                   LocalDateTime leaseUntil, LocalDateTime nextAttemptAt, LocalDateTime lastAttemptAt, UUID assetId,
                   Long deliveryObjectId, long rightsEventId, boolean currentGrant, boolean activePublicReference,
                   CatalogApprovalService.Receipt approval) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JobView request(long actor, UUID id, UUID key, Request request) {
        access.lockCurrent(actor);
        String hash = ImageTransform.sha256(("image-approval-v1\n" + id + "\n" + json.writeValueAsString(request))
                .getBytes(StandardCharsets.UTF_8));
        var replay = jobs.byKey(actor,key);
        if (replay.isPresent()) {
            if (!hash.equals(replay.get().hash())) throw SubmissionErrors.conflict();
            return view(replay.get());
        }
        if (!enabled) throw SubmissionErrors.error(503,"이미지 승인 작업이 비활성화되어 있습니다.");
        if (approval.approval(actor,key).isPresent()) throw SubmissionErrors.conflict();
        if (request.expectedVersion()==null || request.expectedVersion()<0) throw SubmissionInput.invalid("expectedVersion");
        if (request.revisionNo()==null || request.revisionNo()<1) throw SubmissionInput.invalid("revisionNo");
        if (!Boolean.TRUE.equals(request.identityConfirmed())) throw SubmissionInput.invalid("identityConfirmed");
        SubmissionInput.text(request.reason(),500,true,"reason");
        if (request.attachmentId()==null) throw SubmissionInput.invalid("attachmentId");
        var rights = request.rights();
        if (rights==null || !"GRANTED".equals(rights.decision()) || !"PUBLIC_WEB".equals(rights.scope())) {
            throw SubmissionInput.invalid("rights.decision/scope");
        }
        SubmissionInput.text(rights.evidence(),500,true,"rights.evidence");
        var from = instant(rights.validFrom(),"rights.validFrom");
        var until = rights.validUntil()==null ? null : instant(rights.validUntil(),"rights.validUntil");
        var head = locked(id);
        verifyHead(head,request.expectedVersion(),request.revisionNo());
        jobs.requireInactive(head.id());
        var asset = request.attachmentId();
        requireAttachment(head,asset);
        ImageAssetService.ObjectVersion staging;
        try {
            staging = publication.lockVerifiedSubmissionStaging(asset);
        } catch (ImageAssetService.PromotionRejected rejected) {
            throw SubmissionErrors.error(409,"현재 첨부의 검증된 STAGING 객체가 필요합니다.");
        }
        var now = sql.sql("SELECT UTC_TIMESTAMP(6)").query(LocalDateTime.class).single().toInstant(ZoneOffset.UTC);
        if (from.isAfter(now) || (until!=null && !until.isAfter(now))) throw SubmissionInput.invalid("rights.validity");
        long grant = publication.recordRights(asset, ImagePublicationService.Decision.GRANTED,rights.evidence(),
                "member:"+actor,from,until);
        jobs.insert(head,actor,key,hash,request.reason(),asset,grant,staging);
        return view(jobs.byKey(actor,key).orElseThrow());
    }

    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public JobView status(long actor, UUID submission) {
        access.requireCurrent(actor);
        return view(jobs.latest(submission.toString(),false).orElseThrow(SubmissionErrors::missing));
    }

    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public JobView status(long actor, UUID submission, long id) {
        access.requireCurrent(actor);
        return view(jobs.byId(id).filter(job -> job.submission().equals(submission.toString()))
                .orElseThrow(SubmissionErrors::missing));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JobView change(long actor, UUID submission, long id, boolean retry) {
        access.lockCurrent(actor);
        var head = locked(submission);
        var job = jobs.latest(head.id(),true).filter(row -> row.id()==id).orElseThrow(SubmissionErrors::missing);
        verifyHead(head,job.expectedVersion(),job.revision());
        if (retry) {
            if (job.actor()!=actor) throw SubmissionErrors.error(403,"원래 승인 관리자만 재시도할 수 있습니다.");
            jobs.retry(job);
        } else {
            jobs.cancel(job,actor);
        }
        return view(jobs.byId(job.id()).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(ImageApprovalJobs.Job job, ImageAssetService.ObjectVersion delivery) {
        access.lockCurrent(job.actor());
        var head = locked(UUID.fromString(job.submission()));
        jobs.fence(job);
        verifyHead(head,job.expectedVersion(),job.revision());
        var data = requireAttachment(head,job.asset());
        catalog.lockWrites();
        jobs.fence(job);
        images.requirePrepared(job.asset(),promotion(job),delivery);
        var receipt = approval.complete(job.actor(),UUID.fromString(job.key()),job.hash(),job.reason(),head,data,
                sake -> publication.publishPrimary(sake,job.asset(),job.grant(),delivery));
        jobs.complete(job,receipt.sakeNo(),delivery.id());
    }

    ImageAssetService.Promotion promotion(ImageApprovalJobs.Job job) {
        return new ImageAssetService.Promotion(job.grant(),job.staging(),() -> jobs.fence(job),
                delivery -> jobs.deliveryIntent(job,delivery));
    }

    private SubmissionInput requireAttachment(AdminReviewRepository.Head head, UUID asset) {
        var revision = reviews.current(head);
        var data = json.readValue(revision.payload(),SubmissionInput.class);
        if (!asset.toString().equals(revision.attachmentId()) || !asset.equals(data.attachmentId())
                || !Boolean.TRUE.equals(data.imageConsent()) || !"READY".equals(revision.attachmentStatus())
                || data.imageRights()==null || data.imageRights().isBlank()) {
            throw SubmissionErrors.error(409,"현재 리비전의 첨부, 귀속 및 사용자 동의를 확인해 주세요.");
        }
        data.validate();
        return data;
    }

    private AdminReviewRepository.Head locked(UUID id) {
        return reviews.find(id,true).orElseThrow(SubmissionErrors::missing);
    }

    private static void verifyHead(AdminReviewRepository.Head head, long version, int revision) {
        if (!"PENDING".equals(head.status()) || head.version()!=version || head.revision()!=revision) {
            throw SubmissionErrors.conflict();
        }
    }

    private JobView view(ImageApprovalJobs.Job job) {
        var receipt = job.sake()==null ? null : approval.approval(job.actor(),UUID.fromString(job.key())).orElseThrow().receipt();
        boolean processing = "PROCESSING".equals(job.state());
        var references = sql.sql("""
                SELECT EXISTS(SELECT 1 FROM IMAGE_CURRENT_PUBLIC_GRANT WHERE asset_id=? AND id=?) AS current_grant,
                       EXISTS(SELECT 1 FROM SAKE_IMAGE WHERE object_id=? AND active_primary_sake IS NOT NULL) AS linked
                """).params(job.asset().toString(),job.grant(),job.delivery())
                .query((rs,row) -> new References(rs.getBoolean(1),rs.getBoolean(2))).single();
        return new JobView(job.id(),job.submission(),job.revision(),job.state(),job.attempts(),job.failure(),
                processing ? job.due() : null,processing ? null : job.due(),job.lastAttempt(),job.asset(),job.delivery(),
                job.grant(),references.currentGrant(),references.linked(),receipt);
    }

    private record References(boolean currentGrant, boolean linked) {}

    private static Instant instant(String value, String field) {
        if (value==null) throw SubmissionInput.invalid(field);
        try {
            var instant = Instant.parse(value);
            if (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || !instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z")) || instant.getNano()%1000!=0) {
                throw SubmissionInput.invalid(field);
            }
            return instant;
        } catch (DateTimeParseException invalid) {
            throw SubmissionInput.invalid(field);
        }
    }
}
