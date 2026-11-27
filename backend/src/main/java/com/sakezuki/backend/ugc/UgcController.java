package com.sakezuki.backend.ugc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@RestController
class UgcController {
    static final String ROOT = "/api/user/sake-submissions";

    private final SubmissionService submissions;
    private final AttachmentService attachments;
    private final ObjectMapper json;

    UgcController(SubmissionService submissions, AttachmentService attachments, ObjectMapper json) {
        this.submissions = submissions;
        this.attachments = attachments;
        this.json = json;
    }

    @GetMapping("/api/user/catalog/breweries")
    SubmissionService.Page<SubmissionRepository.Option> breweries(@RequestParam(defaultValue = "") String q,
             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return submissions.options(null, false, q, page, size);
    }

    @GetMapping("/api/user/catalog/brands")
    SubmissionService.Page<SubmissionRepository.Option> brands(@RequestParam long breweryNo, @RequestParam(defaultValue = "") String q,
             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return submissions.options(breweryNo, true, q, page, size);
    }

    @PostMapping(path = ROOT, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SubmissionService.Receipt create(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Idempotency-Key", required = false)
                                     String key, HttpServletRequest request) throws IOException {
        return submissions.create(owner(jwt), SubmissionErrors.uuid(key, 422), read(request, SubmissionInput.class));
    }

    @GetMapping(ROOT)
    SubmissionService.Page<SubmissionService.Summary> list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return submissions.list(owner(jwt), page, size);
    }

    @GetMapping(ROOT + "/{id}")
    SubmissionService.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return submissions.detail(owner(jwt), SubmissionErrors.uuid(id, 404));
    }

    @PostMapping(path = ROOT + "/{id}/revisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SubmissionService.Receipt revision(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                       HttpServletRequest request) throws IOException {
        return submissions.revise(owner(jwt), SubmissionErrors.uuid(id, 404), read(request, SubmissionInput.Revision.class));
    }

    @PostMapping(path = ROOT + "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    SubmissionService.Receipt cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                     HttpServletRequest request) throws IOException {
        return submissions.cancel(owner(jwt), SubmissionErrors.uuid(id, 404), read(request, SubmissionInput.Cancel.class));
    }

    @PostMapping(path = ROOT + "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AttachmentService.Upload upload(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                    @RequestPart("file") MultipartFile file, MultipartHttpServletRequest request) throws IOException {
        var requestKey = SubmissionErrors.uuid(key, 422);
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1
                || !request.getParameterMap().isEmpty()) throw SubmissionInput.invalid("file");
        if (file.getSize() > SubmissionLimits.FILE_BYTES) throw SubmissionErrors.error(413, "이미지 용량을 줄여 주세요.");
        try (var input = file.getInputStream()) {
            return attachments.upload(owner(jwt), requestKey, file.getContentType(),
                    input.readNBytes(SubmissionLimits.FILE_BYTES + 1));
        }
    }

    @GetMapping(ROOT + "/uploads/{asset}/preview")
    void uploadPreview(@AuthenticationPrincipal Jwt jwt, @PathVariable String asset,
                       HttpServletResponse response) throws IOException {
        attachments.preview(owner(jwt), SubmissionErrors.uuid(asset, 404), null, response);
    }

    @GetMapping(ROOT + "/{id}/attachments/{asset}")
    void attachedPreview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String asset,
                         HttpServletResponse response) throws IOException {
        attachments.preview(owner(jwt), SubmissionErrors.uuid(asset, 404), SubmissionErrors.uuid(id, 404), response);
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<String> invalidParameter() {
        return ResponseEntity.unprocessableContent().body("필수 입력과 요청 형식을 확인해 주세요.");
    }

    private <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] input = request.getInputStream().readNBytes(SubmissionLimits.JSON_BYTES + 1);
        if (input.length > SubmissionLimits.JSON_BYTES) throw SubmissionErrors.error(413, "제보 본문 용량을 줄여 주세요.");
        try {
            T value = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(input);
            if (value == null) throw SubmissionInput.invalid("body");
            return value;
        } catch (JacksonException invalid) {
            throw SubmissionInput.invalid("body");
        }
    }

    private static long owner(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
