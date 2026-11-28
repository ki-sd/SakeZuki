package com.sakezuki.backend.ugc;

import com.sakezuki.imagecore.ImageTransform;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
class AdminReviewService {
    private final AdminReviewAccess access;
    private final AdminReviewRepository reviews;
    private final SubmissionRepository submissions;
    private final SubmissionService validation;
    private final AttachmentRepository stored;
    private final AttachmentService attachments;
    private final ObjectMapper json;
    private final ImageApprovalJobs jobs;

    AdminReviewService(AdminReviewAccess access, AdminReviewRepository reviews, SubmissionRepository submissions,
                       SubmissionService validation, AttachmentRepository stored, AttachmentService attachments,
                       ObjectMapper json, ImageApprovalJobs jobs) {
        this.access = access;
        this.reviews = reviews;
        this.submissions = submissions;
        this.validation = validation;
        this.stored = stored;
        this.attachments = attachments;
        this.json = json;
        this.jobs = jobs;
    }

    enum Action {
        REQUEST_REVISION("NEEDS_REVISION"), REJECT("REJECTED");
        private final String status;
        Action(String status) { this.status = status; }
    }

    record RevisionRequest(Long expectedVersion, String reason, SubmissionInput data) {}
    record DecisionRequest(Long expectedVersion, Integer revisionNo, String decision, String reason) {}
    record Receipt(String id, String status, long version, int currentRevision) {}
    record Attachment(String id, String status, String stagingState, String rightsState, String previewUrl) {}
    record Revision(int number, Integer parent, long authorNo, String authorRole, int schemaVersion,
                    String kind, JsonNode data, Attachment attachment, LocalDateTime createdAt) {}
    record Detail(AdminReviewRepository.Head submission, Revision original, Revision current,
                  List<Revision> previous, List<AdminReviewRepository.Event> events) {}
    record Candidate(AdminReviewRepository.Candidate catalog, List<String> matchReasons, List<String> reviewDifferences,
                     List<AdminReviewRepository.Source> sourceIdentifiers) {}
    record Duplicates(int currentRevision, long version, int limit, List<String> unavailableCatalogFields,
                      Integer submittedCapacityMl, String submittedVariant, List<Candidate> candidates,
                      boolean sourcesTruncated) {}

    SubmissionService.Page<AdminReviewRepository.Head> list(long actor, AdminReviewRepository.Status status,
                   AdminReviewRepository.Sort sort, String query, int page, int size) {
        access.requireCurrent(actor);
        if (query != null && !query.isEmpty()) SubmissionInput.text(query, 100, false, "q");
        var rows = reviews.list(status, sort, query, SubmissionService.offset(page, size), size + 1);
        return SubmissionService.page(rows, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long actor, UUID id) {
        access.requireCurrent(actor);
        var head = reviews.find(id, false).orElseThrow(SubmissionErrors::missing);
        var versions = reviews.revisions(head).stream().map(r -> view(head.id(), r)).toList();
        var current = versions.getLast();
        if (current.number() != head.revision()) throw new IllegalStateException("submission revision pointer mismatch");
        var names = current.data().path("sake");
        var summary = new AdminReviewRepository.Head(head.id(), head.owner(), head.status(), head.version(), head.revision(),
                names.path("ja").asString(), names.path("ko").asString(), head.createdAt(), head.updatedAt());
        return new Detail(summary, versions.getFirst(), current, versions.stream()
                .filter(r -> r.number() != 1 && r.number() != current.number()).toList(), reviews.events(head));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Duplicates duplicates(long actor, UUID id) {
        access.requireCurrent(actor);
        var head = reviews.find(id, false).orElseThrow(SubmissionErrors::missing);
        var draft = input(reviews.current(head).payload());
        var candidates = reviews.candidates(draft);
        var sourceRows = reviews.sources(candidates.stream().map(row -> row.catalog().no()).toList());
        var sources = new HashMap<Long,List<AdminReviewRepository.Source>>();
        for (var source : sourceRows.subList(0, Math.min(sourceRows.size(), AdminReviewRepository.SOURCE_LIMIT))) {
            sources.computeIfAbsent(source.sakeNo(), ignored -> new ArrayList<>()).add(source);
        }
        var rows = candidates.stream().map(candidate -> compare(draft, candidate,
                        sources.getOrDefault(candidate.catalog().no(), List.of())))
                .toList();
        return new Duplicates(head.revision(), head.version(), AdminReviewRepository.CANDIDATE_LIMIT,
                List.of("capacityMl", "variant"), draft.capacityMl(), draft.variant(), rows,
                sourceRows.size() > AdminReviewRepository.SOURCE_LIMIT);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt revise(long actor, UUID id, RevisionRequest request) {
        access.lockCurrent(actor);
        var head = lockPending(id, request.expectedVersion());
        SubmissionInput.text(request.reason(), 500, true, "reason");
        String payload = validation.payload(request.data());
        var data = request.data();
        var original = input(reviews.current(head).payload());
        if (data.attachmentId() != null && (!data.attachmentId().equals(original.attachmentId())
                || !Objects.equals(data.imageRights(), original.imageRights())
                || !Objects.equals(data.imageConsent(), original.imageConsent()))) {
            throw SubmissionInput.invalid("attachment/consent");
        }
        validation.validateReferences(data);
        int next = head.revision() + 1;
        submissions.revision(actor, "ADMIN", head.id(), next, head.revision(), "ADMIN_REVISION", payload, data.attachmentId());
        reviews.advance(head, next, "PENDING");
        submissions.event(head.id(), next, actor, "ADMIN", "PENDING", "PENDING", request.reason());
        return new Receipt(head.id(), "PENDING", head.version()+1, next);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt decide(long actor, UUID id, UUID key, DecisionRequest request) {
        access.lockCurrent(actor);
        if (request.decision() == null) throw SubmissionInput.invalid("decision");
        Action action;
        try {
            action = Action.valueOf(request.decision());
        } catch (IllegalArgumentException unsupported) {
            throw SubmissionInput.invalid("decision");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) throw SubmissionInput.invalid("expectedVersion");
        if (request.revisionNo() == null || request.revisionNo() < 1) throw SubmissionInput.invalid("revisionNo");
        SubmissionInput.text(request.reason(), 500, true, "reason");
        String hash = ImageTransform.sha256((id + "\n" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
        var prior = reviews.decision(actor, key);
        if (prior.isPresent()) {
            var previous = prior.get();
            if (!hash.equals(previous.hash())) throw SubmissionErrors.conflict();
            return new Receipt(previous.submission(), Action.valueOf(previous.action()).status, previous.version(), previous.revision());
        }
        var head = lockPending(id, request.expectedVersion());
        if (request.revisionNo() != head.revision()) throw SubmissionErrors.conflict();
        reviews.advance(head, head.revision(), action.status);
        submissions.event(head.id(), head.revision(), actor, "ADMIN", "PENDING", action.status, request.reason());
        reviews.recordDecision(actor, key, hash, head, action.name(), request.reason());
        return new Receipt(head.id(), action.status, head.version()+1, head.revision());
    }

    void preview(long actor, UUID id, UUID asset, HttpServletResponse response) throws IOException {
        attachments.preview(() -> {
            access.requireCurrent(actor);
            return stored.adminPreview(actor, id, asset);
        }, response, true);
    }

    private AdminReviewRepository.Head lockPending(UUID id, Long expected) {
        if (expected == null || expected < 0) throw SubmissionInput.invalid("expectedVersion");
        var head = reviews.find(id, true).orElseThrow(SubmissionErrors::missing);
        if (head.version() != expected || !"PENDING".equals(head.status())) throw SubmissionErrors.conflict();
        jobs.requireInactive(head.id());
        return head;
    }

    private SubmissionInput input(String payload) {
        return json.readValue(payload, SubmissionInput.class);
    }

    private Revision view(String id, AdminReviewRepository.Revision row) {
        var attachment = row.attachmentId() == null ? null : new Attachment(row.attachmentId(),
                row.attachmentStatus(), row.stagingState(), row.rightsState(), row.previewable()
                        ? AdminReviewController.ROOT + "/" + id + "/attachments/" + row.attachmentId() : null);
        return new Revision(row.number(), row.parent(), row.author(), row.role(), row.schema(), row.kind(),
                json.readTree(row.payload()), attachment, row.createdAt());
    }

    private static Candidate compare(SubmissionInput draft, AdminReviewRepository.RankedCandidate ranked,
                                     List<AdminReviewRepository.Source> sources) {
        var row = ranked.catalog();
        var matches = new ArrayList<>(row.nameMatches());
        var differences = new ArrayList<String>();
        compareField(matches, differences, "BREWERY", ranked.breweryMatch());
        if (draft.brand() != null || row.brandNo() != null) {
            compareField(matches, differences, "BRAND", ranked.brandMatch());
        }
        if (draft.designation() != null) {
            compareField(matches, differences, "DESIGNATION", ranked.designationMatch());
        }
        return new Candidate(row, matches, differences, sources);
    }

    private static void compareField(List<String> matches, List<String> differences, String field, boolean matchesValue) {
        (matchesValue ? matches : differences).add(field);
    }
}
