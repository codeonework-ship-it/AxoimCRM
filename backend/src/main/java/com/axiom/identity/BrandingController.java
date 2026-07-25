package com.axiom.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated sign-in branding lookup by workspace slug (FR-TEN-015).
 *
 * <p>Excluded from {@code JwtAuthFilter} because the sign-in screen needs it before
 * a token exists. It exposes presentation fields only, and an unknown slug returns
 * the product default rather than a 404 — a distinguishable answer would make this
 * endpoint a workspace-enumeration oracle.
 */
@RestController
@RequestMapping("/api/v1/branding")
public class BrandingController {

    private final BrandingService branding;

    public BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping("/{slug}")
    public BrandingService.Branding forWorkspace(@PathVariable String slug) {
        return branding.publicBranding(slug);
    }
}
