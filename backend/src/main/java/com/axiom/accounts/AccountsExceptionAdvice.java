package com.axiom.accounts;

import com.axiom.common.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Module-local advice so the duplicate envelope can carry structured candidates.
 * Lives here rather than in the shared handler because the shape is specific to
 * FR-ACC-008 and the shared envelope only carries strings.
 */
@RestControllerAdvice
public class AccountsExceptionAdvice {

    public record DuplicateConflict(String code, String message, boolean blocked,
                                    double topConfidence, List<String> blockingRuleCodes,
                                    List<DuplicateService.MatchCandidate> candidates,
                                    List<String> rulesEvaluated, String resolution,
                                    String correlationId) {}

    @ExceptionHandler(DuplicateBlockedException.class)
    public ResponseEntity<DuplicateConflict> duplicates(DuplicateBlockedException ex) {
        DuplicateService.Assessment a = ex.assessment();
        String resolution = a.blocked()
                ? "A blocking duplicate rule matched. Merge into the existing record, or ask a data "
                  + "steward to change the rule — this cannot be overridden on the request."
                : "Review the candidates. To keep both records, resend the request with "
                  + "\"acknowledgeDuplicates\": true and a reason; your decision is recorded.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new DuplicateConflict(
                a.blocked() ? "DUPLICATE_BLOCKED" : "DUPLICATE_WARNING",
                ex.getMessage(), a.blocked(), a.topConfidence(), a.blockingRuleCodes(),
                a.candidates(), a.rulesEvaluated(), resolution,
                MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
