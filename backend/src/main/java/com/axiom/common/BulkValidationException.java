package com.axiom.common;

import java.util.List;

public class BulkValidationException extends RuntimeException {
    private final List<String> details;

    public BulkValidationException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> details() { return details; }
}
