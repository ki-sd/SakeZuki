package com.sakezuki.backend.ugc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping(AdminReviewController.ROOT)
class AdminReviewController {
    static final String ROOT = "/api/admin/sake-submissions";
    private final AdminReviewService reviews;

    AdminReviewController(AdminReviewService reviews) {
        this.reviews = reviews;
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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> invalidParameter() {
        return ResponseEntity.unprocessableContent().body("필수 입력과 요청 형식을 확인해 주세요.");
    }

    private static long actor(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
