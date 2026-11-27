package com.sakezuki.backend.ugc;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

final class SubmissionErrors {
    private SubmissionErrors() {}

    static ResponseStatusException error(int status, String message) {
        return new ResponseStatusException(HttpStatusCode.valueOf(status), message);
    }

    static ResponseStatusException missing() {
        return error(404, "제보 또는 첨부를 찾을 수 없습니다.");
    }

    static ResponseStatusException conflict() {
        return error(409, "제보 상태, 버전 또는 요청 키가 현재 요청과 일치하지 않습니다.");
    }

    static UUID uuid(String value, int status) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) return parsed;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // A malformed ID is handled before accessing the database.
        }
        if (status == 404) throw missing();
        throw error(status, "올바른 Idempotency-Key가 필요합니다.");
    }
}
