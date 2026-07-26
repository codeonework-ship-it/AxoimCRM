package com.axiom.ui;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The theme surface: what the product offers, and what this user chose.
 *
 * <p>Authenticated, because a theme preference belongs to a user. The catalogue
 * itself is not a secret, but there is no anonymous screen that needs it — the
 * login page paints from its own small set of chips — so there is no reason to
 * open an unauthenticated route and one good reason not to.
 *
 * <p>Every call here lands in {@code activity.user_activity} through
 * {@code UserActivityFilter}, so a theme change is tracked with the actor, the
 * time, the outcome and the correlation id, with nothing written by hand.
 */
@RestController
@RequestMapping("/api/v1/ui")
public class ThemeController {

    private final ThemeService service;

    public ThemeController(ThemeService service) {
        this.service = service;
    }

    /**
     * The catalogue, this user's selection and the effective theme, in one call.
     * The client needs all three before first paint; see ThemeService.ThemeState.
     */
    @GetMapping("/theme")
    public ThemeService.ThemeState theme() {
        return service.state();
    }

    /**
     * {@code themeCode} is nullable on purpose — sending null clears the choice
     * and returns the user to following the product default, which is a thing a
     * user can reasonably want and which "delete the preference" would express
     * less clearly.
     */
    public record ThemeChoice(@Size(max = 32) String themeCode) {}

    @PutMapping("/theme")
    public ThemeService.ThemeState choose(@RequestBody ThemeChoice body) {
        return service.choose(body == null ? null : body.themeCode());
    }

    /**
     * Adoption per theme, for the admin surface. Read-only, and it answers the
     * only question that matters before retiring a theme: who is on it.
     */
    @GetMapping("/theme/adoption")
    public List<Map<String, Object>> adoption() {
        return service.adoption();
    }
}
