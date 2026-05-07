package com.majm.rag.common.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
    String code,
    String message,
    Instant timestamp,
    String traceId,
    List<FieldError> fieldErrors
) {

    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(code, message, Instant.now(), traceId, null);
    }

    public static ErrorResponse withFieldErrors(String code, String message, String traceId,
                                                List<FieldError> fieldErrors) {
        return new ErrorResponse(code, message, Instant.now(), traceId, fieldErrors);
    }

    public record FieldError(String field, String message) {}
}
