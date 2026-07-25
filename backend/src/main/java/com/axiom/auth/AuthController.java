package com.axiom.auth;

import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(@NotBlank String tenantSlug,
                               @NotBlank String email,
                               @NotBlank String password) {}

    public record UserDto(UUID id, String displayName, String email, String role) {}

    public record TenantDto(UUID id, String slug, String name) {}

    public record LoginResponse(String token, UserDto user, TenantDto tenant) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @jakarta.validation.Valid LoginRequest request) {
        AuthService.AuthResult r = authService.login(request.tenantSlug(), request.email(), request.password());
        return new LoginResponse(
                r.token(),
                new UserDto(r.userId(), r.displayName(), r.email(), r.role()),
                new TenantDto(r.tenantId(), r.tenantSlug(), r.tenantName()));
    }

    /** Returns the authenticated principal bound by JwtAuthFilter. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Principal p = TenantContext.get();
        return Map.of(
                "userId", p.userId(),
                "tenantId", p.tenantId(),
                "role", p.role(),
                "displayName", p.displayName());
    }
}
