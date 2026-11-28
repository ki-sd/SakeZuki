package com.sakezuki.backend.ugc;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import java.io.IOException;

final class UgcRequestJson {
    private UgcRequestJson() {}

    private static final JsonMapper REQUEST_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Integer, config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Float, config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Boolean, config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
            .build();

    static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        byte[] input = request.getInputStream().readNBytes(SubmissionLimits.JSON_BYTES + 1);
        if (input.length > SubmissionLimits.JSON_BYTES) throw SubmissionErrors.error(413, "제보 본문 용량을 줄여 주세요.");
        try {
            T value = REQUEST_JSON.readerFor(type).readValue(input);
            if (value == null) throw SubmissionInput.invalid("body");
            return value;
        } catch (JacksonException invalid) {
            throw SubmissionInput.invalid("body");
        }
    }

}
