package com.axiom.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Session governance surface (FR-TEN-010). */
@RestController
@RequestMapping("/api/v1/security/sessions")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    public record RevokeRequest(@NotBlank String reason) {}

    @GetMapping
    public List<SessionService.SessionRow> list(
            @RequestParam(defaultValue = "false") boolean includeEnded) {
        return sessions.list(includeEnded);
    }

    @GetMapping("/policy")
    public SessionService.SessionPolicy policy() {
        return sessions.policy(com.axiom.tenancy.TenantContext.get().tenantId());
    }

    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @RequestBody @Valid RevokeRequest request) {
        sessions.revoke(id, request.reason());
    }

    @PostMapping("/users/{userId}/revoke-all")
    public Map<String, Object> revokeAll(@PathVariable UUID userId, @RequestBody @Valid RevokeRequest request) {
        int ended = sessions.revokeAllForUser(userId, request.reason());
        return Map.of("ended", ended,
                "message", ended == 0 ? "That user had no active sessions."
                        : ended + " session(s) were ended. Their tokens stop working on the next request.");
    }
}
