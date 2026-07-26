package com.axiom.security;

import com.axiom.common.ApiError;
import com.axiom.common.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.List;

/**
 * Turns the security backstops declared in V310 into the responses they deserve.
 *
 * <h2>Why this exists</h2>
 * The administrator/auditor floor and the activity allowlist are enforced by
 * database triggers precisely so that they hold for code paths this module does
 * not own — {@code /api/v1/admin/users/{id}/active} among them. Without this
 * advice, hitting one of those triggers through somebody else's controller
 * produces a 500 and the generic "An unexpected error occurred", which tells the
 * administrator nothing and looks like a bug rather than a refused change.
 *
 * <p>So the trigger's own message is surfaced, with the status that matches what
 * happened: 409 for the floor (the request was well-formed, the state forbids
 * it) and 422 for a rejected activity payload (the request itself was not
 * acceptable). {@code PGException.getServerErrorMessage()} carries the hint the
 * trigger raised, which is where "promote another user first" lives.
 *
 * <p>Ordered ahead of {@code GlobalExceptionHandler}, whose {@code Exception}
 * catch-all would otherwise win and swallow the message.
 *
 * <p>This is the sanctioned pattern in this codebase: see
 * {@code com.axiom.accounts.AccountsExceptionAdvice}, which does the same for
 * duplicate blocking.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityConstraintAdvice {

    /** Tenant administrator/auditor floor, V310. */
    static final String FLOOR = "AX001";
    /** Activity detail allowlist, V310 — FR-AUD-014. */
    static final String ACTIVITY_ALLOWLIST = "AX002";
    /** Activity log is append-only, V310. */
    static final String ACTIVITY_APPEND_ONLY = "AX003";

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> constraint(DataAccessException ex) {
        SQLException sql = sqlCause(ex);
        String state = sql == null ? null : sql.getSQLState();
        if (state == null) throw ex;

        return switch (state) {
            case FLOOR -> respond(HttpStatus.CONFLICT, "TENANT_ROLE_FLOOR", sql);
            case ACTIVITY_ALLOWLIST -> respond(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVITY_DETAIL_REJECTED", sql);
            case ACTIVITY_APPEND_ONLY -> respond(HttpStatus.CONFLICT, "ACTIVITY_APPEND_ONLY", sql);
            default -> throw ex;   // not ours; let the shared handler decide
        };
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, SQLException sql) {
        return ResponseEntity.status(status).body(new ApiError(
                code, cleanMessage(sql), List.of(), MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    /**
     * The trigger wrote a sentence for a human; give them the sentence.
     *
     * <p>Read via {@code getMessage()} rather than the driver's richer
     * {@code getServerErrorMessage()} because the PostgreSQL driver is a
     * <i>runtime</i>-scope dependency in this build — compiling against
     * {@code PSQLException} here would make the API module depend on a specific
     * driver. That is why V310 puts the entire refusal, remedy included, in the
     * message rather than splitting it across message/hint/detail.
     */
    static String cleanMessage(SQLException sql) {
        String raw = sql.getMessage();
        if (raw == null || raw.isBlank()) return "The change was refused by a security constraint.";
        int newline = raw.indexOf('\n');
        String firstLine = (newline < 0 ? raw : raw.substring(0, newline)).trim();
        return firstLine.startsWith("ERROR: ") ? firstLine.substring("ERROR: ".length()) : firstLine;
    }

    private static SQLException sqlCause(Throwable ex) {
        Throwable cursor = ex;
        int hops = 0;
        while (cursor != null && hops++ < 16) {
            if (cursor instanceof SQLException sql) return sql;
            cursor = cursor.getCause();
        }
        return null;
    }
}
