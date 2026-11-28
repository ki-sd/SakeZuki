package com.sakezuki.backend.ugc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record SubmissionLimits(
        @Value("${ugc.uploads-per-day:10}") int uploadsPerDay,
        @Value("${ugc.submissions-per-day:5}") int submissionsPerDay,
        @Value("${ugc.revisions-per-day:10}") int revisionsPerDay,
        @Value("${ugc.open-submissions:10}") int openSubmissions,
        @Value("${ugc.member-reserved-bytes:268435456}") long reservedBytes,
        @Value("${ugc.unlinked-hours:24}") int unlinkedHours) {
    static final int FILE_BYTES = 10 * 1024 * 1024;
    static final int MULTIPART_BYTES = 11 * 1024 * 1024;
    static final int JSON_BYTES = 16 * 1024;

    SubmissionLimits {
        if (uploadsPerDay < 1 || submissionsPerDay < 1 || revisionsPerDay < 1 || openSubmissions < 1
                || reservedBytes < FILE_BYTES || unlinkedHours < 1) {
            throw new IllegalArgumentException("invalid UGC limits");
        }
    }
}
