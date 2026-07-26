package com.axiom.access;

import com.axiom.common.ApiError;
import com.axiom.common.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The unauthenticated surface. Everything under {@code /api/v1/public/} is
 * reachable with no token at all, which is stated here as loudly as possible
 * because it is the only place in the product where that is true.
 *
 * <p>Two endpoints, both deliberately narrow:
 * <ul>
 *   <li>{@code POST /public/trial-requests} — self-registration for a 30-day trial.
 *       It creates a queued request, never a tenant. Nothing here can provision
 *       anything; that needs a platform operator to approve.</li>
 *   <li>{@code POST /public/trial-activations/{token}} — sets the password on an
 *       account whose one-time link the caller holds. It cannot create, name or
 *       enumerate an account; the token is the entire authority.</li>
 * </ul>
 *
 * <p><b>No response here reveals whether a company, workspace or user exists.</b>
 * A first-time submission and a repeat submission return the same status, the
 * same shape and the same wording; only the reference differs, and the caller
 * cannot tell which they got. Every activation failure returns one message.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicAccessController {

    private final TrialRequestService trials;
    private final TrialActivationService activations;

    public PublicAccessController(TrialRequestService trials, TrialActivationService activations) {
        this.trials = trials;
        this.activations = activations;
    }

    /** Exactly the payload the public form posts. */
    public record TrialRequestPayload(String companyName, String workEmail, String fullName,
                                      String jobTitle, String companySize, String country, String notes) {}

    public record ActivationPayload(String password) {}

    @PostMapping("/trial-requests")
    public ResponseEntity<Object> requestTrial(@RequestBody(required = false) TrialRequestPayload payload,
                                               HttpServletRequest http) {
        TrialRequestService.Submission submission = payload == null
                ? new TrialRequestService.Submission(null, null, null, null, null, null, null)
                : new TrialRequestService.Submission(payload.companyName(), payload.workEmail(),
                        payload.fullName(), payload.jobTitle(), payload.companySize(),
                        payload.country(), payload.notes());

        TrialRequestService.Decision decision = trials.submit(submission,
                clientIp(http), truncate(http.getHeader("User-Agent")));

        if (decision.reference() == null) {
            // A refusal. ApiError shape, so the browser client reads `message`
            // through the same path as every other failure.
            return ResponseEntity.status(decision.httpStatus())
                    .body(ApiError.of(decision.code(), decision.message(), MDC.get(CorrelationIdFilter.MDC_KEY)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", decision.reference());
        body.put("status", decision.status());
        body.put("trialDays", decision.trialDays());
        body.put("message", decision.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/trial-activations/{token}")
    public TrialActivationService.ActivationOutcome activate(@PathVariable String token,
                                                             @RequestBody ActivationPayload payload,
                                                             HttpServletRequest http) {
        return activations.redeem(token, payload == null ? null : payload.password(), clientIp(http));
    }

    /**
     * Best-effort client address, mirroring {@code AuthController.clientIp} — which
     * is package-private and not ours to widen. Used only for rate limiting and the
     * event trail, never for identity: a forged {@code X-Forwarded-For} can dodge a
     * throttle, it cannot become a tenant (ADR-001 rule 4).
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    /** A user agent is telemetry, not a field to store unbounded input from. */
    private static String truncate(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() <= 400 ? userAgent : userAgent.substring(0, 400);
    }
}
