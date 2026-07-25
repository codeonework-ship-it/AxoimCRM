package com.axiom.common;

import java.util.List;

/** Uniform error envelope for every non-2xx API response. */
public record ApiError(String code, String message, List<String> details, String correlationId) {

    public static ApiError of(String code, String message, String correlationId) {
        return new ApiError(code, message, List.of(), correlationId);
    }
}
