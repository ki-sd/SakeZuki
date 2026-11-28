package com.sakezuki.backend.ugc;

import com.sakezuki.imagecore.ImageTransform;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
class SubmissionService {
    private final SubmissionRepository submissions;
    private final AttachmentRepository attachments;
    private final SubmissionLimits limits;
    private final ObjectMapper json;

    SubmissionService(SubmissionRepository submissions, AttachmentRepository attachments, SubmissionLimits limits,
                      ObjectMapper json) {
        this.submissions = submissions;
        this.attachments = attachments;
        this.limits = limits;
        this.json = json;
    }

    record Receipt(String id, String status, long version, int currentRevision) {}
    record Summary(String id, String status, long version, int currentRevision,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {}
    record RevisionView(int number, Integer parent, int schemaVersion, String kind, JsonNode data,
                        String attachmentId, String previewUrl, LocalDateTime createdAt) {}
    record Detail(Summary submission, List<RevisionView> revisions, List<SubmissionRepository.Event> events) {}
    record Page<T>(List<T> items, int page, int size, boolean hasMore) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt create(long owner, UUID key, SubmissionInput data) {
        String payload = payload(data);
        String hash = ImageTransform.sha256(payload.getBytes(StandardCharsets.UTF_8));
        submissions.lockActiveMember(owner);
        var prior = submissions.existing(owner, key);
        if (prior.isPresent()) {
            if (!hash.equals(prior.get().hash())) throw SubmissionErrors.conflict();
            return new Receipt(prior.get().id(), "PENDING", 0, 1);
        }
        if (submissions.recentSubmissions(owner) >= limits.submissionsPerDay()
                || submissions.openSubmissions(owner) >= limits.openSubmissions()) throw quota();
        validateReferences(data);
        String id = UUID.randomUUID().toString();
        submissions.insert(owner, id, key, hash);
        attach(owner, id, data);
        submissions.revision(owner, id, 1, null, "INITIAL", payload, data.attachmentId());
        submissions.initialize(id, owner);
        submissions.event(id, 1, owner, null, "PENDING", "사용자 제출");
        return new Receipt(id, "PENDING", 0, 1);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt revise(long owner, UUID id, SubmissionInput.Revision request) {
        submissions.lockActiveMember(owner);
        var prior = locked(owner, id, request.expectedVersion());
        if (!"NEEDS_REVISION".equals(prior.status())) throw SubmissionErrors.conflict();
        if (submissions.recentRevisions(owner) >= limits.revisionsPerDay()) throw quota();
        var data = request.data();
        String payload = payload(data);
        validateReferences(data);
        attach(owner, prior.id(), data);
        int next = prior.revision() + 1;
        submissions.revision(owner, prior.id(), next, prior.revision(), "MEMBER_REVISION", payload, data.attachmentId());
        submissions.change(prior, owner, next, "PENDING");
        submissions.event(prior.id(), next, owner, prior.status(), "PENDING", "사용자 보완 제출");
        return new Receipt(prior.id(), "PENDING", prior.version() + 1, next);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(long owner, UUID id, SubmissionInput.Cancel request) {
        submissions.lockActiveMember(owner);
        var prior = locked(owner, id, request.expectedVersion());
        if (!open(prior.status())) throw SubmissionErrors.conflict();
        SubmissionInput.text(request.reason(), 500, false, "reason");
        submissions.change(prior, owner, prior.revision(), "CANCELLED");
        submissions.event(prior.id(), prior.revision(), owner, prior.status(), "CANCELLED",
                request.reason() == null ? "사용자 취소" : request.reason());
        return new Receipt(prior.id(), "CANCELLED", prior.version() + 1, prior.revision());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long owner, UUID id) {
        var item = submissions.find(owner, id, false).orElseThrow(SubmissionErrors::missing);
        var versions = submissions.revisions(owner, item.id()).stream().map(r -> new RevisionView(
                r.number(), r.parent(), r.schemaVersion(), r.kind(), json.readTree(r.payload()), r.attachment(),
                open(item.status()) && r.attachment() != null
                        ? UgcController.ROOT + "/" + item.id() + "/attachments/" + r.attachment() : null, r.createdAt())).toList();
        return new Detail(summary(item), versions, submissions.events(owner, item.id()));
    }

    Page<Summary> list(long owner, int page, int size) {
        var rows = submissions.list(owner, offset(page, size), size + 1).stream().map(SubmissionService::summary).toList();
        return page(rows, page, size);
    }

    Page<SubmissionRepository.Option> options(Long brewery, boolean brands, String query, int page, int size) {
        if (brands && (brewery == null || brewery < 1)) throw SubmissionInput.invalid("breweryNo");
        if (query != null && !query.isEmpty()) SubmissionInput.text(query, 100, false, "q");
        return page(submissions.options(brewery, brands, query == null ? "" : query, offset(page, size), size + 1),
                page, size);
    }

    private String payload(SubmissionInput data) {
        if (data == null) throw SubmissionInput.invalid("data");
        data.validate();
        return json.writeValueAsString(data);
    }

    private void validateReferences(SubmissionInput data) {
        Long brewery = data.brewery().existingNo();
        if (brewery != null && !submissions.breweryExists(brewery)) throw SubmissionInput.invalid("brewery.existingNo");
        if (data.brand() != null && data.brand().existingNo() != null
                && !submissions.brandBelongs(brewery, data.brand().existingNo())) {
            throw SubmissionInput.invalid("brand.existingNo");
        }
    }

    private void attach(long owner, String id, SubmissionInput data) {
        if (data.attachmentId() != null) attachments.claim(owner, data.attachmentId(), id);
    }

    private SubmissionRepository.Submission locked(long owner, UUID id, Long expected) {
        if (expected == null || expected < 0) throw SubmissionInput.invalid("expectedVersion");
        var prior = submissions.find(owner, id, true).orElseThrow(SubmissionErrors::missing);
        if (prior.version() != expected) throw SubmissionErrors.conflict();
        return prior;
    }

    private static Summary summary(SubmissionRepository.Submission item) {
        return new Summary(item.id(), item.status(), item.version(), item.revision(), item.createdAt(), item.updatedAt());
    }

    private static boolean open(String status) {
        return "PENDING".equals(status) || "NEEDS_REVISION".equals(status);
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 50) throw SubmissionInput.invalid("page/size");
        return page * size;
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
    }

    static org.springframework.web.server.ResponseStatusException quota() {
        return SubmissionErrors.error(429, "제보 또는 첨부 한도에 도달했습니다.");
    }
}
