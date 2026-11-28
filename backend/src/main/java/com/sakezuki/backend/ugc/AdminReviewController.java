package com.sakezuki.backend.ugc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;

@RestController
@RequestMapping(AdminReviewController.ROOT)
class AdminReviewController {
    static final String ROOT = "/api/admin/sake-submissions";
    private final AdminReviewService reviews;
    private final CatalogApprovalService approvals;
    private final ImageApprovalService imageApprovals;

    AdminReviewController(AdminReviewService reviews, CatalogApprovalService approvals, ImageApprovalService imageApprovals) {
        this.reviews = reviews;
        this.approvals = approvals;
        this.imageApprovals = imageApprovals;
    }

    @GetMapping
    SubmissionService.Page<AdminReviewRepository.Head> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) AdminReviewRepository.Status status,
            @RequestParam(defaultValue = "UPDATED") AdminReviewRepository.Sort sort,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviews.list(actor(jwt), status, sort, q, page, size);
    }

    @GetMapping("/{id}")
    AdminReviewService.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return reviews.detail(actor(jwt), SubmissionErrors.uuid(id,404));
    }

    @GetMapping("/{id}/duplicates")
    AdminReviewService.Duplicates duplicates(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return reviews.duplicates(actor(jwt), SubmissionErrors.uuid(id,404));
    }

    @GetMapping("/{id}/attachments/{asset}")
    void preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String asset,
                 HttpServletResponse response) throws IOException {
        reviews.preview(actor(jwt), SubmissionErrors.uuid(id,404), SubmissionErrors.uuid(asset,404), response);
    }

    @PostMapping(path="/{id}/revisions", consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AdminReviewService.Receipt revise(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                      HttpServletRequest request) throws IOException {
        return reviews.revise(actor(jwt), SubmissionErrors.uuid(id,404),
                UgcRequestJson.read(request, AdminReviewService.RevisionRequest.class));
    }

    @PostMapping(path="/{id}/decisions", consumes=MediaType.APPLICATION_JSON_VALUE)
    AdminReviewService.Receipt decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                       @RequestHeader(value="Idempotency-Key", required=false) String key,
                                       HttpServletRequest request) throws IOException {
        return reviews.decide(actor(jwt), SubmissionErrors.uuid(id,404), SubmissionErrors.uuid(key,422),
                UgcRequestJson.read(request, AdminReviewService.DecisionRequest.class));
    }

    @PostMapping(path="/{id}/approvals", consumes=MediaType.APPLICATION_JSON_VALUE)
    CatalogApprovalService.Receipt approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                            @RequestHeader(value="Idempotency-Key", required=false) String key,
                                            HttpServletRequest request) throws IOException {
        var body = UgcRequestJson.read(request, CatalogApprovalService.Request.class);
        return approvalWrite(() -> approvals.approve(actor(jwt), SubmissionErrors.uuid(id,404),
                SubmissionErrors.uuid(key,422),body));
    }

    @PostMapping(path="/{id}/image-approvals", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImageApprovalService.JobView> approveImage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                    @RequestHeader(value="Idempotency-Key", required=false) String key, HttpServletRequest request)
            throws IOException {
        var submission = SubmissionErrors.uuid(id,404);
        var body = UgcRequestJson.read(request,ImageApprovalService.Request.class);
        var job = approvalWrite(() -> imageApprovals.request(actor(jwt),submission,SubmissionErrors.uuid(key,422),body));
        return ResponseEntity.accepted().location(URI.create(ROOT+"/"+submission+"/approval-job")).body(job);
    }

    @GetMapping("/{id}/approval-job")
    ImageApprovalService.JobView imageApproval(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return imageApprovals.status(actor(jwt),SubmissionErrors.uuid(id,404));
    }

    @GetMapping("/{id}/approval-job/{job}")
    ImageApprovalService.JobView imageApproval(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                              @PathVariable long job) {
        return imageApprovals.status(actor(jwt),SubmissionErrors.uuid(id,404),job);
    }

    @PostMapping("/{id}/approval-job/{job}/retry")
    ImageApprovalService.JobView retryImage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                           @PathVariable long job) {
        return approvalWrite(() -> imageApprovals.change(actor(jwt),SubmissionErrors.uuid(id,404),job,true));
    }

    @PostMapping("/{id}/approval-job/{job}/cancel")
    ImageApprovalService.JobView cancelImage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                            @PathVariable long job) {
        return approvalWrite(() -> imageApprovals.change(actor(jwt),SubmissionErrors.uuid(id,404),job,false));
    }

    private static <T> T approvalWrite(Supplier<T> write) {
        try {
            return write.get();
        } catch (DataAccessException failure) {
            if (!ImageApprovalWorker.isLockContention(failure)) throw failure;
            throw SubmissionErrors.error(503,"승인 작업의 DB 잠금이 경합 중입니다. 같은 요청 키로 다시 확인해 주세요.");
        }
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> invalidParameter() {
        return ResponseEntity.unprocessableContent().body("필수 입력과 요청 형식을 확인해 주세요.");
    }

    private static long actor(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
