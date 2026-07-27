package com.axiom.i18n;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only translation surface consumed by the frontend I18nProvider.
 *
 * Optionally authenticated — see the class comment on {@link I18nService} and the
 * conditional skip rule in {@code JwtAuthFilter#shouldNotFilter}.
 */
@RestController
@RequestMapping("/api/v1/i18n")
public class I18nController {
    private final I18nService service;

    public I18nController(I18nService service) {
        this.service = service;
    }

    @GetMapping("/locales")
    public List<I18nService.LocaleRow> locales() {
        return service.locales();
    }

    @GetMapping("/bundle/{locale}")
    public Map<String, String> bundle(@PathVariable String locale) {
        return service.bundle(locale);
    }

    @GetMapping("/phrases/{locale}")
    public Map<String, String> phraseBundle(@PathVariable String locale) {
        return service.phraseBundle(locale);
    }
}
