package com.axiom.auth;

import com.axiom.common.UnauthorizedException;
import com.axiom.identity.PasswordPolicyService;
import com.axiom.identity.ServiceCredentialService;
import com.axiom.identity.SessionService;
import com.axiom.identity.StepUpService;
import com.axiom.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final PasswordPolicyService passwordPolicy;
    private final StepUpService stepUp;
    private final SessionService sessions;
    private final JwtService jwtService;
    private final ServiceCredentialService serviceCredentials;

    public AuthController(AuthService authService, PasswordPolicyService passwordPolicy, StepUpService stepUp,
                          SessionService sessions, JwtService jwtService,
                          ServiceCredentialService serviceCredentials) {
        this.authService = authService;
        this.passwordPolicy = passwordPolicy;
        this.stepUp = stepUp;
        this.sessions = sessions;
        this.jwtService = jwtService;
        this.serviceCredentials = serviceCredentials;
    }

    public record LoginRequest(@NotBlank String tenantSlug, @NotBlank String email,
                               @NotBlank String password) {}
    public record SwitchTenantRequest(@NotBlank String tenantSlug) {}
    public record MfaVerifyRequest(@NotBlank String challengeToken, String code, String recoveryCode) {}
    public record StepUpRequest(@NotBlank String password, String code) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record ServiceTokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {}
    public record UserDto(UUID id, String displayName, String email, String role, boolean platformUser) {}
    public record TenantDto(UUID id, String slug, String name) {}
    public record LoginResponse(String token, UserDto user, TenantDto tenant,
                                boolean mfaRequired, String mfaChallengeToken, String notice) {}

    /**
     * Sign-in orchestration. The throttle read, the progressive delay and the
     * attempt record all sit here rather than inside {@link AuthService#login},
     * because a failed sign-in rolls that transaction back — and an attempt record
     * that disappears on failure is worse than none: lockout would never trigger.
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        String userAgent = http.getHeader("User-Agent");
        UUID tenantId = null;
        try {
            tenantId = authService.tenantIdForSlug(request.tenantSlug());
        } catch (RuntimeException ignored) {
            // An unknown workspace is refused below by the same path as a bad
            // credential, so it stays indistinguishable.
        }
        if (tenantId != null) {
            PasswordPolicyService.ThrottleState throttle =
                    passwordPolicy.throttleState(tenantId, request.email());
            if (throttle.lockedOut()) {
                passwordPolicy.recordAttempt(tenantId, null, request.email(), "LOCKED_OUT", ip, userAgent);
                throw new UnauthorizedException("Too many failed sign-in attempts. Try again after "
                        + throttle.lockedUntil() + ", or ask an administrator to reset your password.");
            }
            sleep(throttle.delayMillis());
        }
        try {
            AuthService.AuthResult result = authService.login(request.tenantSlug(), request.email(),
                    request.password(), ip, userAgent);
            if (tenantId != null) {
                passwordPolicy.recordAttempt(tenantId, result.userId(), request.email(),
                        result.mfaRequired() ? "MFA_REQUIRED" : "SUCCESS", ip, userAgent);
            }
            return response(result);
        } catch (AuthService.LoginOutcome outcome) {
            if (outcome.tenantId() != null) {
                passwordPolicy.recordAttempt(outcome.tenantId(), outcome.userId(), request.email(),
                        outcome.outcome(), ip, userAgent);
            }
            throw outcome.unwrap();
        }
    }

    @PostMapping("/mfa/verify")
    public LoginResponse verifyMfa(@RequestBody @Valid MfaVerifyRequest request, HttpServletRequest http) {
        Claims claims;
        try {
            claims = jwtService.parse(request.challengeToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("That sign-in attempt has expired. Start again.");
        }
        if (!JwtService.TYPE_MFA_CHALLENGE.equals(claims.get("typ", String.class))) {
            throw new UnauthorizedException("That token cannot be used to complete a second factor");
        }
        UUID tenantId = UUID.fromString(claims.get("tid", String.class));
        UUID userId = UUID.fromString(claims.get("uid", String.class));
        String ip = clientIp(http);
        String userAgent = http.getHeader("User-Agent");
        try {
            AuthService.AuthResult result = authService.completeMfa(tenantId, userId, request.code(),
                    request.recoveryCode(), ip, userAgent);
            passwordPolicy.recordAttempt(tenantId, userId, claims.getSubject(), "SUCCESS", ip, userAgent);
            return response(result);
        } catch (AuthService.LoginOutcome outcome) {
            passwordPolicy.recordAttempt(tenantId, userId, claims.getSubject(), outcome.outcome(), ip, userAgent);
            throw outcome.unwrap();
        }
    }

    /** OAuth 2.0 client-credentials style exchange for an integration (FR-TEN-013). */
    @PostMapping("/service-token")
    public Map<String, Object> serviceToken(@RequestBody @Valid ServiceTokenRequest request,
                                            HttpServletRequest http) {
        ServiceCredentialService.AuthenticatedCredential credential =
                serviceCredentials.authenticate(request.clientId(), request.clientSecret());
        JwtService.IssuedToken issued = jwtService.issueAccessToken(credential.tenantId(), credential.id(),
                CrmRole.INTEGRATION.name(), credential.name(), request.clientId(), false, null, null, null);
        sessions.createSession(credential.tenantId(), credential.id(), false, request.clientId(),
                credential.name(), CrmRole.INTEGRATION.name(), issued.jti(), "SERVICE",
                issued.issuedAt(), issued.expiresAt(), clientIp(http), http.getHeader("User-Agent"), null, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.token());
        body.put("token_type", "Bearer");
        body.put("expires_in", Duration.between(issued.issuedAt(), issued.expiresAt()).toSeconds());
        body.put("scope", String.join(" ", credential.scopes()));
        return body;
    }

    @PostMapping("/step-up")
    public StepUpService.StepUpResult stepUp(@RequestBody @Valid StepUpRequest request) {
        return stepUp.stepUp(request.password(), request.code());
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        passwordPolicy.changeOwnPassword(request.currentPassword(), request.newPassword());
        return Map.of("message", "Your password was changed. Your other sessions were signed out.");
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        authService.logout();
        return Map.of("message", "Signed out. This token no longer works.");
    }

    @GetMapping("/tenants")
    public List<AuthService.TenantOption> tenants() { return authService.tenants(); }

    @PostMapping("/switch-tenant")
    public LoginResponse switchTenant(@RequestBody @Valid SwitchTenantRequest request, HttpServletRequest http) {
        return response(authService.switchTenant(request.tenantSlug(), clientIp(http),
                http.getHeader("User-Agent")));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Principal p = TenantContext.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", p.userId());
        body.put("tenantId", p.tenantId());
        body.put("role", p.role());
        body.put("displayName", p.displayName());
        body.put("email", p.email());
        body.put("platformUser", CrmRole.current(p.role()).platform());
        body.put("sessionId", TenantContext.sessionJti());
        // FR-TEN-011: impersonation must be visibly indicated throughout the
        // session, which the UI can only do if every identity read says so.
        TenantContext.Impersonator impersonator = TenantContext.impersonator();
        body.put("impersonating", impersonator != null);
        if (impersonator != null) {
            body.put("impersonatorEmail", impersonator.email());
            body.put("impersonatorName", impersonator.displayName());
        }
        return body;
    }

    /**
     * Best-effort client address. {@code X-Forwarded-For} is trusted because this
     * deployment sits behind a reverse proxy; it is used only for network rules and
     * telemetry, never for tenant identity (ADR-001 rule 4).
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static LoginResponse response(AuthService.AuthResult r) {
        return new LoginResponse(r.token(),
                new UserDto(r.userId(), r.displayName(), r.email(), r.role(), r.platformUser()),
                new TenantDto(r.tenantId(), r.tenantSlug(), r.tenantName()),
                r.mfaRequired(), r.mfaChallengeToken(), r.notice());
    }
}
