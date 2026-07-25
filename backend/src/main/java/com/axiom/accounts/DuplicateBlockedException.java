package com.axiom.accounts;

/**
 * FR-ACC-008 — the write was refused (blocking rule) or held for acknowledgement
 * (warning rule). Either way the candidates travel with the failure: a refusal a
 * user cannot inspect is a refusal they will route around.
 */
public class DuplicateBlockedException extends RuntimeException {

    private final DuplicateService.Assessment assessment;

    public DuplicateBlockedException(String message, DuplicateService.Assessment assessment) {
        super(message);
        this.assessment = assessment;
    }

    public DuplicateService.Assessment assessment() {
        return assessment;
    }
}
