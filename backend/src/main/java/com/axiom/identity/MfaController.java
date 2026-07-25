package com.axiom.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Second-factor enrolment and management for the signed-in user (FR-TEN-008).
 *
 * <p>Removing a factor and reissuing recovery codes both require a fresh step-up:
 * they are the two operations an attacker who has stolen a live session would reach
 * for first.
 */
@RestController
@RequestMapping("/api/v1/security/mfa")
public class MfaController {

    private final MfaService mfa;
    private final StepUpService stepUp;

    public MfaController(MfaService mfa, StepUpService stepUp) {
        this.mfa = mfa;
        this.stepUp = stepUp;
    }

    public record ConfirmRequest(@NotBlank String code) {}
    public record DisableRequest(@NotBlank String reason) {}

    @GetMapping
    public MfaService.MfaStatus status() {
        return mfa.status();
    }

    @PostMapping("/enrol")
    public MfaService.EnrolmentResult enrol() {
        return mfa.enrol();
    }

    @PostMapping("/confirm")
    public MfaService.ConfirmationResult confirm(@RequestBody @Valid ConfirmRequest request) {
        return mfa.confirm(request.code());
    }

    @PostMapping("/recovery-codes")
    public MfaService.ConfirmationResult regenerate() {
        stepUp.requireStepUp("Reissuing recovery codes");
        return mfa.regenerateRecoveryCodes();
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@RequestBody @Valid DisableRequest request) {
        stepUp.requireStepUp("Removing your second factor");
        mfa.disable(request.reason());
    }
}
