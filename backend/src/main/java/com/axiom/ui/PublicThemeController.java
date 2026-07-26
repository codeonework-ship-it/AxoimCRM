package com.axiom.ui;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The theme catalogue, without a token.
 *
 * <h2>Why this exists separately from ThemeController</h2>
 * The sign-in screen carries a theme strip, so someone can set the product's
 * appearance before they have an account bound — and at that moment there is no
 * token, no tenant and therefore no preference to read. {@link ThemeController}
 * needs all three. Rather than weaken that endpoint to serve both cases, the
 * anonymous case gets its own route with strictly less in it.
 *
 * <p>Under {@code /api/v1/public/}, which {@code JwtAuthFilter} already treats as
 * unguarded, so no change to the authentication rules was needed to add it.
 *
 * <h2>What is deliberately NOT here</h2>
 * No preference, read or written. A theme chosen on the sign-in screen is cached
 * in the browser only; it is attached to the user by {@code ThemeController} once
 * they are actually signed in. Writing a preference for an unauthenticated caller
 * would mean trusting a client-supplied user id, which ADR-001 rule 4 forbids
 * outright.
 *
 * <p>The catalogue itself is not sensitive — five colour swatches and their names
 * — and it is the same list every tenant sees, so serving it anonymously leaks
 * nothing about who a caller is or which workspace they belong to.
 */
@RestController
@RequestMapping("/api/v1/public/ui")
public class PublicThemeController {

    private final ThemeService service;

    public PublicThemeController(ThemeService service) {
        this.service = service;
    }

    @GetMapping("/themes")
    public List<ThemeService.Theme> themes() {
        return service.catalogue();
    }
}
